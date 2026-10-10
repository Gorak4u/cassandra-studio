import { useCallback, useEffect, useState } from "react";
import { createPortal } from "react-dom";
import { SettingsDialog } from "./SettingsDialog";
import { dismissVersion, dismissedVersion, settingsApi, type UpdateStatus } from "./settingsApi";
import "./settings.css";

/**
 * The header's "Settings" button with its dialog, and the non-blocking update banner (NFR-UPD).
 * The update check runs once at start, only when enabled and not offline (the engine decides), and never
 * downloads: the banner links to the release page, opened in the system browser.
 */
export function AppSettings() {
  const [open, setOpen] = useState(false);
  const [update, setUpdate] = useState<UpdateStatus | null>(null);

  const check = useCallback(() => {
    settingsApi.updates().then(setUpdate).catch(() => setUpdate(null)); // never in the way: no toast
  }, []);

  useEffect(check, [check]);

  const show = update?.updateAvailable && update.latest && dismissedVersion() !== update.latest;
  return (
    <>
      <button className="btn small" onClick={() => setOpen(true)} aria-haspopup="dialog">Settings</button>
      {open && <SettingsDialog onClose={() => setOpen(false)} onSaved={check} />}
      {show && createPortal(
        <div className="update-banner" role="status" data-testid="update-banner">
          <span>
            Cassandra Studio {update!.latest} is available (you have {update!.current}).{" "}
            <a href={update!.url ?? undefined} target="_blank" rel="noopener noreferrer">Release notes and download</a>
          </span>
          <button className="btn small" aria-label={`Dismiss update ${update!.latest}`}
            onClick={() => { dismissVersion(update!.latest!); setUpdate(null); }}>
            Dismiss
          </button>
        </div>,
        document.body,
      )}
    </>
  );
}
