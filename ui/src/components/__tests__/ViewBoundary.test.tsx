import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";

vi.mock("../../lib/studioApi", () => ({ studioApi: { reportUiError: vi.fn(() => Promise.resolve()) } }));

import { studioApi } from "../../lib/studioApi";
import { ViewBoundary } from "../ViewBoundary";

let fail = true;
function Flaky() {
  if (fail) throw new Error("boom");
  return <p>panel ok</p>;
}

afterEach(cleanup);

describe("ViewBoundary", () => {
  it("shows the error in place, keeps siblings, reports it and reloads the view", () => {
    vi.spyOn(console, "error").mockImplementation(() => undefined);
    render(
      <div>
        <p>tab bar</p>
        <ViewBoundary name="acme · gclogs"><Flaky /></ViewBoundary>
      </div>,
    );
    expect(screen.getByRole("alert").textContent).toContain("boom");
    expect(screen.getByText("tab bar")).toBeTruthy();
    expect(vi.mocked(studioApi.reportUiError)).toHaveBeenCalledWith(expect.objectContaining({ message: "acme · gclogs: boom" }));

    fail = false;
    fireEvent.click(screen.getByRole("button", { name: "Reload view" }));
    expect(screen.getByText("panel ok")).toBeTruthy();
    expect(screen.queryByRole("alert")).toBeNull();
  });
});
