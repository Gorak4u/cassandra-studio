import { act, cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { HelpLink } from "../../../components/HelpLink";
import { Markdown } from "../Markdown";
import { parseMarkdown } from "../markdown";

afterEach(cleanup);

describe("HelpLink", () => {
  it("has an accessible label and opens the guide at the panel's section", async () => {
    render(<HelpLink topic="monitoring" />);
    const btn = screen.getByRole("button", { name: "Help: Monitoring" });
    expect(btn.getAttribute("aria-expanded")).toBe("false");
    fireEvent.click(btn);
    const drawer = await screen.findByRole("dialog", { name: "Help" });
    expect(btn.getAttribute("aria-expanded")).toBe("true");
    // The target heading gets focus, so screen readers start at the section.
    const heading = within(drawer).getByRole("heading", { name: "Monitoring" });
    expect(document.activeElement).toBe(heading);
    expect(within(drawer).getByRole("heading", { name: "Health rules and thresholds" })).toBeTruthy();
    expect((within(drawer).getByRole("combobox", { name: "Guide" }) as HTMLSelectElement).value).toBe("guide/user-guide.md");
  });

  it("follows links between guides, goes back, and closes with Escape", async () => {
    render(<HelpLink topic="ops-repair" />);
    fireEvent.click(screen.getByRole("button", { name: "Help: Repair runbook" }));
    const drawer = await screen.findByRole("dialog", { name: "Help" });
    expect(within(drawer).getByRole("heading", { level: 3, name: /Runbook: repair/ })).toBeTruthy();
    fireEvent.click(within(drawer).getAllByRole("button", { name: "scrub" })[0]);
    expect(within(drawer).getByRole("heading", { level: 3, name: "Runbook: scrub" })).toBeTruthy();
    fireEvent.click(within(drawer).getByRole("button", { name: "Back" }));
    expect(within(drawer).getByRole("heading", { level: 3, name: /Runbook: repair/ })).toBeTruthy();
    act(() => { fireEvent.keyDown(window, { key: "Escape" }); });
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(document.activeElement).toBe(screen.getByRole("button", { name: "Help: Repair runbook" }));
  });
});

describe("Markdown renderer", () => {
  it("renders text with HTML in it as text, never as markup", () => {
    const { container } = render(
      <Markdown blocks={parseMarkdown("Hi <img src=x onerror=alert(1)> **b**\n\n[x](javascript:alert(1))")} idPrefix="t-" headingOffset={0}
        resolve={() => ({ kind: "none" })} onNavigate={() => undefined} />,
    );
    expect(container.querySelector("img")).toBeNull();
    expect(container.querySelector("a")).toBeNull();
    expect(container.textContent).toContain("<img src=x onerror=alert(1)>");
    expect(container.querySelector("strong")?.textContent).toBe("b");
  });

  it("renders external links to open outside the app", () => {
    const { container } = render(
      <Markdown blocks={parseMarkdown("[site](https://example.org)")} idPrefix="t-" headingOffset={0}
        resolve={(h) => ({ kind: "external", url: h })} onNavigate={() => undefined} />,
    );
    const a = container.querySelector("a")!;
    expect(a.getAttribute("href")).toBe("https://example.org");
    expect(a.getAttribute("rel")).toBe("noopener noreferrer");
    expect(a.getAttribute("target")).toBe("_blank");
  });
});
