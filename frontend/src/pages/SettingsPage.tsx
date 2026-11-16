import { useState } from 'react';
import { PageState } from '../components/PageState';
import { StatusBadge } from '../components/StatusBadge';
import { Card, ConfirmDialog, PageHeader } from '../components/ui';
import { useToast } from '../context/ToastContext';
import { useAsyncAction } from '../hooks/useAsyncAction';
import { useQuery } from '../hooks/useQuery';
import { dateTime } from '../lib/format';
import { integrationService } from '../services/integrations';
import type { CheckResult, SettingView } from '../types/api';

export function SettingsPage() {
  const toast = useToast();
  const config = useQuery(() => integrationService.config(), []);
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [saving, setSaving] = useState<SettingView | null>(null);
  const [checks, setChecks] = useState<CheckResult[] | null>(null);
  const [confirmCheck, setConfirmCheck] = useState(false);

  const save = useAsyncAction((setting: SettingView) => integrationService.updateSetting(setting.key, drafts[setting.key] ?? setting.value));
  const check = useAsyncAction(() => integrationService.healthCheck());

  const confirmSave = async () => {
    if (!saving) {
      return;
    }
    const updated = await save.run(saving);
    if (!updated) {
      return;
    }
    setSaving(null);
    setDrafts((current) => {
      const next = { ...current };
      delete next[updated.key];
      return next;
    });
    toast.success(`${updated.key} is now ${updated.value}.`);
    config.reload();
  };

  const runCheck = async () => {
    const result = await check.run();
    if (!result) {
      return;
    }
    setConfirmCheck(false);
    const results = [result.odoo, result.soap, result.rest];
    setChecks(results);
    const failed = results.filter((item) => !item.ok).length;
    if (failed === 0) {
      toast.success('All three connections answered.');
    } else {
      toast.error(`${failed} of 3 connections did not answer. See the health check results.`);
    }
  };

  const data = config.data;

  return (
    <>
      <PageHeader title="Settings" subtitle="Connections, runtime settings and health checks. Secrets are never shown here." />
      <PageState loading={config.loading} error={config.error} onRetry={config.reload}>
        {data && (
          <div className="space-y-5">
            <Card title="Connections">
              <div className="overflow-x-auto">
                <table className="min-w-full divide-y divide-slate-200">
                  <caption className="sr-only">Where FinBridge connects to. Set through environment variables.</caption>
                  <thead className="bg-slate-50">
                    <tr>
                      <th className="th" scope="col">Connection</th>
                      <th className="th" scope="col">Address</th>
                      <th className="th" scope="col">Detail</th>
                      <th className="th text-right" scope="col">Connect timeout</th>
                      <th className="th text-right" scope="col">Read timeout</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {data.connections.map((connection) => (
                      <tr key={connection.name}>
                        <th scope="row" className="td text-left font-medium">{connection.name}</th>
                        <td className="td break-all font-mono text-xs">{connection.url}</td>
                        <td className="td">{connection.detail}</td>
                        <td className="td text-right tabular-nums">{connection.connectTimeoutMs / 1000} s</td>
                        <td className="td text-right tabular-nums">{connection.readTimeoutMs / 1000} s</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <p className="border-t border-slate-200 px-4 py-3 text-xs text-slate-500">
                Addresses and timeouts come from the environment (.env locally) and change with a restart.
              </p>
            </Card>

            <Card title="Secrets">
              <ul className="divide-y divide-slate-100">
                {data.secrets.map((secret) => (
                  <li key={secret.name} className="flex flex-wrap items-center gap-3 px-4 py-3 text-sm">
                    <span className="w-40 font-mono text-xs font-semibold">{secret.name}</span>
                    <StatusBadge status={secret.configured ? 'CONFIGURED' : 'MISSING'} tone={secret.configured ? 'green' : 'red'} />
                    <span className="text-slate-600">{secret.purpose}</span>
                  </li>
                ))}
              </ul>
              <p className="border-t border-slate-200 px-4 py-3 text-xs text-slate-500">
                A secret's value never leaves the backend. This page can only say whether each one is set.
              </p>
            </Card>

            <Card title="Runtime settings">
              <ul className="divide-y divide-slate-100">
                {data.settings.map((setting) => {
                  const draft = drafts[setting.key] ?? setting.value;
                  const number = Number(draft);
                  const valid = draft.trim() !== '' && Number.isInteger(number) && number >= setting.min && number <= setting.max;
                  const changed = draft !== setting.value;
                  return (
                    <li key={setting.key} className="flex flex-wrap items-end gap-4 px-4 py-4">
                      <div className="min-w-64 flex-1">
                        <p className="font-mono text-xs font-semibold text-navy-800">{setting.key}</p>
                        <p className="mt-0.5 text-sm text-slate-700">{setting.description}</p>
                        <p className="mt-0.5 text-xs text-slate-500">
                          Allowed {setting.min} to {setting.max}. Default {setting.defaultValue}.
                          {setting.overridden && setting.updatedAt ? ` Changed ${dateTime(setting.updatedAt)} by user ${setting.updatedBy}.` : ' Using the default.'}
                        </p>
                      </div>
                      <label className="block w-28">
                        <span className="label">Value</span>
                        <input
                          className="input tabular-nums"
                          type="number"
                          min={setting.min}
                          max={setting.max}
                          step={1}
                          value={draft}
                          aria-invalid={!valid}
                          onChange={(event) => setDrafts((current) => ({ ...current, [setting.key]: event.target.value }))}
                        />
                      </label>
                      <button type="button" className="btn-primary" disabled={!changed || !valid} onClick={() => { save.clearError(); setSaving(setting); }}>
                        Save
                      </button>
                    </li>
                  );
                })}
              </ul>
              <p className="border-t border-slate-200 px-4 py-3 text-xs text-slate-500">
                These apply from the next synchronization run or payment retry, without a restart.
              </p>
            </Card>

            <Card
              title="Health checks"
              actions={
                <button type="button" className="btn-secondary" onClick={() => { check.clearError(); setConfirmCheck(true); }}>
                  Run health check
                </button>
              }
            >
              {checks ? (
                <ul className="divide-y divide-slate-100">
                  {checks.map((item) => (
                    <li key={item.target} className="flex flex-wrap items-center gap-3 px-4 py-3 text-sm">
                      <span className="w-16 font-semibold uppercase text-slate-700">{item.target}</span>
                      <StatusBadge status={item.ok ? 'UP' : 'DOWN'} />
                      <span className="tabular-nums text-slate-500">{item.durationMs} ms</span>
                      {!item.ok && <span className="text-red-800">{item.errorCode}: {item.message}</span>}
                      {item.ok && item.message && <span className="text-slate-600">{item.message}</span>}
                    </li>
                  ))}
                </ul>
              ) : (
                <p className="px-4 py-4 text-sm text-slate-500">No check has been run on this page yet.</p>
              )}
            </Card>
          </div>
        )}
      </PageState>

      {saving && (
        <ConfirmDialog title="Change this setting" confirmLabel="Save setting" onConfirm={() => void confirmSave()} onCancel={() => setSaving(null)} running={save.running} error={save.error}>
          <p>
            <span className="font-mono text-xs font-semibold">{saving.key}</span> changes from <strong>{saving.value}</strong> to{' '}
            <strong>{drafts[saving.key]}</strong>.
          </p>
          <p className="text-slate-500">{saving.description}.</p>
        </ConfirmDialog>
      )}
      {confirmCheck && (
        <ConfirmDialog title="Run a health check" confirmLabel="Run health check" onConfirm={() => void runCheck()} onCancel={() => setConfirmCheck(false)} running={check.running} error={check.error}>
          <p>FinBridge calls Odoo, the SOAP payment service and the simulator now and logs each call. Nothing is changed in any system.</p>
        </ConfirmDialog>
      )}
    </>
  );
}
