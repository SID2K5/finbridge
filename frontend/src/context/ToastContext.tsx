import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from 'react';

/** Short success and failure messages after an action, shown top right and announced to screen readers. */

type Tone = 'success' | 'error' | 'info';

interface Toast {
  id: number;
  tone: Tone;
  text: string;
}

interface ToastApi {
  success: (text: string) => void;
  error: (text: string) => void;
  info: (text: string) => void;
}

const ToastContext = createContext<ToastApi | null>(null);

const TONE_CLASSES: Record<Tone, string> = {
  success: 'border-emerald-300 bg-emerald-50 text-emerald-900',
  error: 'border-red-300 bg-red-50 text-red-900',
  info: 'border-slate-300 bg-white text-slate-800',
};

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const nextId = useRef(1);

  const dismiss = useCallback((id: number) => {
    setToasts((current) => current.filter((toast) => toast.id !== id));
  }, []);

  const push = useCallback(
    (tone: Tone, text: string) => {
      const id = nextId.current++;
      setToasts((current) => [...current.slice(-3), { id, tone, text }]);
      // Errors stay longer: they usually need to be read twice.
      window.setTimeout(() => dismiss(id), tone === 'error' ? 12_000 : 6_000);
    },
    [dismiss],
  );

  const api = useMemo<ToastApi>(
    () => ({
      success: (text) => push('success', text),
      error: (text) => push('error', text),
      info: (text) => push('info', text),
    }),
    [push],
  );

  return (
    <ToastContext.Provider value={api}>
      {children}
      <div className="pointer-events-none fixed right-4 top-4 z-50 flex w-96 max-w-[calc(100vw-2rem)] flex-col gap-2" aria-live="polite">
        {toasts.map((toast) => (
          <div
            key={toast.id}
            role={toast.tone === 'error' ? 'alert' : 'status'}
            className={`pointer-events-auto flex items-start gap-3 rounded-md border px-4 py-3 text-sm shadow-md ${TONE_CLASSES[toast.tone]}`}
          >
            <span className="flex-1 break-words">{toast.text}</span>
            <button
              type="button"
              className="rounded px-1 text-xs font-semibold opacity-70 hover:opacity-100"
              onClick={() => dismiss(toast.id)}
              aria-label="Dismiss message"
            >
              Close
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast(): ToastApi {
  const context = useContext(ToastContext);
  if (!context) {
    throw new Error('useToast must be used inside ToastProvider');
  }
  return context;
}
