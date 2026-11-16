import { useEffect, useId, useRef, type ReactNode } from 'react';
import type { ApiError } from '../services/api';

/** Small building blocks shared by the pages: page header, filter bar, KPI tile, dialogs, definition list. */

export function PageHeader({ title, subtitle, actions }: { title: string; subtitle?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="mb-5 flex flex-wrap items-start justify-between gap-3">
      <div>
        <h1 className="text-xl font-semibold text-navy-900">{title}</h1>
        {subtitle && <p className="mt-1 text-sm text-slate-500">{subtitle}</p>}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </div>
  );
}

export function Card({ title, actions, children, className = '' }: { title?: string; actions?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={`card ${className}`}>
      {(title || actions) && (
        <div className="flex items-center justify-between gap-3 border-b border-slate-200 px-4 py-3">
          {title && <h2 className="text-sm font-semibold text-navy-900">{title}</h2>}
          {actions}
        </div>
      )}
      {children}
    </section>
  );
}

/** A row of filters above a table. onReset appears only while a filter is set. */
export function FilterBar({ children, onReset, active }: { children: ReactNode; onReset: () => void; active: boolean }) {
  return (
    <div className="flex flex-wrap items-end gap-3 border-b border-slate-200 bg-slate-50/60 px-4 py-3">
      {children}
      {active && (
        <button type="button" className="btn-secondary" onClick={onReset}>
          Clear filters
        </button>
      )}
    </div>
  );
}

export function Field({ label, children, className = '' }: { label: string; children: ReactNode; className?: string }) {
  return (
    <label className={`block ${className}`}>
      <span className="label">{label}</span>
      {children}
    </label>
  );
}

interface KpiProps {
  label: string;
  /** null or undefined means "no data": the tile then says so instead of showing a zero. */
  value: string | number | null | undefined;
  hint?: ReactNode;
  tone?: 'default' | 'attention' | 'good';
  emptyText?: string;
}

export function KpiTile({ label, value, hint, tone = 'default', emptyText = 'No data yet' }: KpiProps) {
  const hasValue = value !== null && value !== undefined && value !== '';
  const accent = tone === 'attention' ? 'border-l-amber-500' : tone === 'good' ? 'border-l-emerald-500' : 'border-l-navy-600';
  return (
    <div className={`card border-l-4 px-4 py-3.5 ${accent}`}>
      <p className="text-xs font-medium uppercase tracking-wide text-slate-500">{label}</p>
      {hasValue ? (
        <p className="mt-1.5 break-words text-2xl font-semibold tabular-nums text-navy-900">{value}</p>
      ) : (
        <p className="mt-2.5 text-sm italic text-slate-400">{emptyText}</p>
      )}
      {hint && <p className="mt-1 text-xs text-slate-500">{hint}</p>}
    </div>
  );
}

export function DefinitionList({ items }: { items: { term: string; value: ReactNode }[] }) {
  return (
    <dl className="grid grid-cols-1 gap-x-6 gap-y-3 px-4 py-4 sm:grid-cols-2 lg:grid-cols-3">
      {items.map((item) => (
        <div key={item.term}>
          <dt className="text-xs font-medium uppercase tracking-wide text-slate-500">{item.term}</dt>
          <dd className="mt-0.5 break-words text-sm text-slate-800">{item.value ?? '–'}</dd>
        </div>
      ))}
    </dl>
  );
}

export function InlineError({ error }: { error: ApiError | null }) {
  if (!error) {
    return null;
  }
  return (
    <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-800" role="alert">
      <p>{error.message}</p>
      {error.fieldErrors.length > 0 && (
        <ul className="mt-1 list-disc pl-5">
          {error.fieldErrors.map((fieldError) => (
            <li key={`${fieldError.field}-${fieldError.message}`}>
              {fieldError.field}: {fieldError.message}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

interface ModalProps {
  title: string;
  onClose: () => void;
  children: ReactNode;
  footer?: ReactNode;
  wide?: boolean;
  /** While true the dialog cannot be dismissed (an action is in flight). */
  busy?: boolean;
}

/** A modal dialog: Escape and the backdrop close it, focus moves into it, and it is labelled for screen readers. */
export function Modal({ title, onClose, children, footer, wide, busy }: ModalProps) {
  const titleId = useId();
  const panel = useRef<HTMLDivElement>(null);

  useEffect(() => {
    panel.current?.focus();
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !busy) {
        onClose();
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose, busy]);

  return (
    <div
      className="fixed inset-0 z-40 flex items-start justify-center overflow-y-auto bg-navy-950/50 p-4 pt-[10vh]"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget && !busy) {
          onClose();
        }
      }}
    >
      <div
        ref={panel}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        className={`w-full rounded-lg bg-white shadow-xl outline-none ${wide ? 'max-w-3xl' : 'max-w-lg'}`}
      >
        <div className="border-b border-slate-200 px-5 py-3.5">
          <h2 id={titleId} className="text-base font-semibold text-navy-900">
            {title}
          </h2>
        </div>
        <div className="space-y-4 px-5 py-4 text-sm text-slate-700">{children}</div>
        {footer && <div className="flex justify-end gap-2 border-t border-slate-200 bg-slate-50 px-5 py-3">{footer}</div>}
      </div>
    </div>
  );
}

interface ConfirmProps {
  title: string;
  children: ReactNode;
  confirmLabel: string;
  onConfirm: () => void;
  onCancel: () => void;
  running: boolean;
  error?: ApiError | null;
  danger?: boolean;
  confirmDisabled?: boolean;
}

/** The confirm step every mutating action goes through. */
export function ConfirmDialog({ title, children, confirmLabel, onConfirm, onCancel, running, error, danger, confirmDisabled }: ConfirmProps) {
  return (
    <Modal
      title={title}
      onClose={onCancel}
      busy={running}
      footer={
        <>
          <button type="button" className="btn-secondary" onClick={onCancel} disabled={running}>
            Cancel
          </button>
          <button
            type="button"
            className={danger ? 'btn-danger' : 'btn-primary'}
            onClick={onConfirm}
            disabled={running || confirmDisabled}
          >
            {running ? 'Working…' : confirmLabel}
          </button>
        </>
      }
    >
      {children}
      <InlineError error={error ?? null} />
    </Modal>
  );
}
