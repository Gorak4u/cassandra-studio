import { Component, type ErrorInfo, type ReactNode } from "react";
import { studioApi } from "../lib/studioApi";
import { uiErrors } from "./StudioDataDialog";

/** Errors in one view stay in that view; they are written to the local crash log (NFR-OBS). */
export class ViewBoundary extends Component<{ name: string; children: ReactNode }, { error: Error | null }> {
  state: { error: Error | null } = { error: null };

  static getDerivedStateFromError(error: Error) {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    reportUiError(`${this.props.name}: ${error.message}`, error.stack, info.componentStack ?? undefined);
  }

  render() {
    if (!this.state.error) return this.props.children;
    return (
      <div className="pad" role="alert">
        <div className="notice error">This view stopped because of an error: {this.state.error.message}. It was written to the local crash log.</div>
        <button className="btn" onClick={() => this.setState({ error: null })}>Reload view</button>
      </div>
    );
  }
}

let reported = 0;
/** Sends a UI error to the engine's local crash log (at most 50 per window) and keeps it for "Copy diagnostics". */
export function reportUiError(message: string, stack?: string, componentStack?: string) {
  uiErrors.push(message.slice(0, 500));
  if (uiErrors.length > 20) uiErrors.shift();
  if (reported++ >= 50) return;
  studioApi.reportUiError({ message, stack, componentStack }).catch(() => undefined);
}
