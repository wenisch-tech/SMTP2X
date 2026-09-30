import { test, expect } from "@playwright/test";
import { mkdir } from "node:fs/promises";

async function login(page, email = "admin@smtp2x.local") {
  await page.goto("/login");
  await page.getByLabel("Email", { exact: true }).fill(email);
  await page.getByLabel("Password", { exact: true }).fill("fixture-password");
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Configured routing" }),
  ).toBeVisible();
  await expect(
    page
      .locator(".flow-canvas")
      .getByRole("button", { name: /Infrastructure alerts/ }),
  ).toBeVisible();
}

test("README screenshots", async ({ page }) => {
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await login(page);
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.setViewportSize({
    width: 1440,
    height: Math.ceil(
      await page.locator(".main").evaluate((el) => el.scrollHeight),
    ),
  });
  await expect(
    page.locator("#recent-messages .activity-time").first(),
  ).toContainText("30 Sept");
  await mkdir("docs", { recursive: true });
  // Deterministic update label; the screenshot otherwise contains the real rendered UI and database fixtures.
  await page
    .locator("#updated")
    .evaluate((el) => (el.textContent = "Updated just now"));
  await page.screenshot({ path: "docs/smtp2x-dashboard.png", fullPage: true });
  await page.getByRole("link", { name: "Routing rules", exact: true }).click();
  await page
    .locator(".rule-card")
    .filter({ hasText: "Infrastructure alerts" })
    .getByRole("button", { name: "Edit rule" })
    .click();
  await expect(page.locator("#action-options input:checked")).toHaveCount(2);
  await page.getByLabel("Rule name", { exact: true }).blur();
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.setViewportSize({
    width: 1440,
    height: Math.ceil(
      await page.locator(".main").evaluate((el) => el.scrollHeight),
    ),
  });
  await page.screenshot({ path: "docs/smtp2x-rules.png", fullPage: true });
  expect(errors).toEqual([]);
});

test("flow highlights shared connections, retains selection, and reports stale data", async ({
  page,
}) => {
  await login(page);
  const shared = page
    .locator(".flow-canvas")
    .getByRole("button", { name: /Team notifications/ });
  await shared.click();
  await expect(page.locator(".flow-canvas .flow-node.connected")).toHaveCount(
    3,
  );
  await expect(page.locator("#flow-detail")).toContainText(
    "2 connected rule(s)",
  );
  await page.getByRole("button", { name: "Refresh", exact: false }).click();
  await expect(shared).toHaveAttribute("aria-pressed", "true");
  await expect(page.locator(".flow-wires .highlight")).toHaveCount(4);
  await page.route("**/api/v1/dashboard", (route) =>
    route.fulfill({
      status: 503,
      contentType: "application/json",
      body: JSON.stringify({ error: "Temporarily unavailable" }),
    }),
  );
  await page.getByRole("button", { name: "Refresh", exact: false }).click();
  await expect(page.getByRole("status")).toContainText(
    "Showing last available data",
  );
  await expect(shared).toBeVisible();
});

test("rule edit supports multiple actions, inline webhook creation, and persistence", async ({
  page,
}) => {
  await login(page);
  await page.goto("/rules");
  await page.getByRole("button", { name: "Create rule", exact: false }).click();
  await page
    .getByLabel("Rule name", { exact: true })
    .fill("Browser-created route");
  await page
    .getByLabel("Recipient pattern", { exact: true })
    .fill("browser@example.com");
  await page.getByLabel("Subject text").fill("browser test");
  await page.getByLabel("Subject matching").selectOption("EQUALS");
  await page
    .locator("#action-options")
    .getByLabel(/Platform issue tracker/)
    .check();
  await page
    .getByRole("button", { name: "Create action", exact: false })
    .click();
  await page.getByLabel("Action name", { exact: true }).fill("Browser webhook");
  await page.getByLabel("Action type").selectOption("WEBHOOK");
  await page
    .getByLabel("Webhook URL", { exact: true })
    .fill("https://hooks.example.com/browser");
  await page
    .locator("#action-dialog")
    .getByRole("button", { name: "Create action", exact: true })
    .click();
  await expect(page.locator("#action-dialog")).not.toBeVisible();
  await expect(page.getByLabel("Rule name", { exact: true })).toHaveValue(
    "Browser-created route",
  );
  await expect(page.locator("#action-options input:checked")).toHaveCount(2);
  await page.getByRole("button", { name: "Save rule" }).click();
  await expect(page.locator("#page-notice")).toContainText("Rule saved");
  await page.reload();
  await page
    .locator(".rule-card")
    .filter({ hasText: "Browser-created route" })
    .getByRole("button", { name: "Edit rule" })
    .click();
  await expect(page.locator("#action-options input:checked")).toHaveCount(2);
  await expect(page.getByLabel("Subject matching")).toHaveValue("EQUALS");
  await page.getByLabel("Rule enabled").uncheck();
  await page.getByRole("button", { name: "Save rule" }).click();
  await expect(
    page.locator(".rule-card").filter({ hasText: "Browser-created route" }),
  ).toContainText("Disabled");
  await page.goto("/actions");
  await expect(
    page.locator("article").filter({ hasText: "Browser webhook" }),
  ).toContainText("Browser-created route");
});

test("dialog cancellation and failed saves preserve the draft", async ({
  page,
}) => {
  await login(page);
  await page.goto("/rules");
  await page.getByRole("button", { name: "Create rule", exact: false }).click();
  await page.getByLabel("Rule name", { exact: true }).fill("Keep this draft");
  await page.getByLabel("Match all recipients").check();
  await page
    .getByRole("button", { name: "Create action", exact: false })
    .click();
  await page
    .getByLabel("Action name", { exact: true })
    .fill("Discard action draft");
  await page
    .locator("#action-dialog")
    .getByRole("button", { name: "Cancel", exact: true })
    .click();
  await expect(page.getByLabel("Rule name", { exact: true })).toHaveValue(
    "Keep this draft",
  );
  await page.getByRole("button", { name: "Save rule" }).click();
  await expect(page.locator("#rule-error")).toContainText(
    "Select at least one action",
  );
  await page
    .locator("#action-options")
    .getByLabel(/Team notifications/)
    .check();
  await page.route("**/api/v1/rules", (route) =>
    route.request().method() === "POST"
      ? route.fulfill({
          status: 400,
          contentType: "application/json",
          body: '{"error":"Please retry this save"}',
        })
      : route.continue(),
  );
  await page.getByRole("button", { name: "Save rule" }).click();
  await expect(page.locator("#rule-error")).toContainText("Please retry");
  await expect(page.getByLabel("Rule name", { exact: true })).toHaveValue(
    "Keep this draft",
  );
});

test("viewer access, keyboard flow selection, and mobile layouts", async ({
  page,
}) => {
  await login(page, "viewer@example.com");
  await expect(
    page.getByRole("link", { name: "Administration", exact: true }),
  ).toHaveCount(0);
  const node = page
    .locator(".flow-canvas")
    .getByRole("button", { name: /Customer requests/ });
  await node.focus();
  await page.keyboard.press("Enter");
  await expect(node).toHaveAttribute("aria-pressed", "true");
  await expect(page.locator("#flow-detail").getByRole("link")).toHaveText(
    "View rules ↗",
  );
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.locator(".mobile-routes")).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: "test-results/mobile-dashboard.png",
    fullPage: true,
  });
  await page.goto("/rules");
  await expect(
    page.getByRole("button", { name: "Create rule", exact: false }),
  ).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "Edit rule", exact: false }),
  ).toHaveCount(0);
  await page.goto("/actions");
  await expect(
    page.getByRole("button", { name: "Create action", exact: false }),
  ).toHaveCount(0);
});

test("empty configurations, missing references, and disabled actions are clear", async ({
  page,
}) => {
  await login(page);
  const original = await page.evaluate(() => window.smtp2x.api("/dashboard"));
  await page.route("**/api/v1/dashboard", (route) =>
    route.fulfill({ json: { ...original, rules: [], actions: [] } }),
  );
  await page.getByRole("button", { name: "Refresh", exact: false }).click();
  await expect(page.getByText("Build your first route")).toBeVisible();
  await page.unroute("**/api/v1/dashboard");
  const missing = "11111111-1111-1111-1111-111111111111";
  const modified = {
    ...original,
    rules: [{ ...original.rules[0], actionIds: [missing] }],
  };
  await page.route("**/api/v1/dashboard", (route) =>
    route.fulfill({ json: modified }),
  );
  await page.getByRole("button", { name: "Refresh", exact: false }).click();
  await expect(
    page
      .locator(".flow-canvas")
      .getByRole("button", { name: /Unavailable action/ }),
  ).toBeVisible();
  await page.goto("/rules");
  await page.getByRole("button", { name: "Create rule", exact: false }).click();
  await expect(
    page.locator("#action-options").getByLabel(/Legacy incident feed/),
  ).toBeDisabled();
  await page.getByRole("button", { name: "Cancel", exact: true }).click();
  await page
    .locator(".rule-card")
    .filter({ hasText: "Legacy monitoring" })
    .getByRole("button", { name: "Edit rule" })
    .click();
  await expect(
    page.locator("#action-options").getByLabel(/Legacy incident feed/),
  ).toBeChecked();
  await page
    .locator("#action-options")
    .getByLabel(/Legacy incident feed/)
    .uncheck();
});

test("all workspace pages render without browser errors", async ({ page }) => {
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await login(page);
  for (const path of [
    "/messages",
    "/deliveries",
    "/audit",
    "/administration",
    "/actions",
    "/rules",
  ]) {
    await page.goto(path);
    await expect(page.locator("h1")).toBeVisible();
    await expect(page.locator("#page-notice")).not.toBeVisible();
    await page.setViewportSize({ width: 390, height: 844 });
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
      path,
    ).toBe(true);
    await page.setViewportSize({ width: 1440, height: 1100 });
  }
  expect(errors).toEqual([]);
});

test("GitLab action validation, type switching, and escaped names", async ({
  page,
}) => {
  await login(page);
  await page.goto("/actions");
  await page
    .getByRole("button", { name: "Create action", exact: false })
    .click();
  const form = page.locator("#action-form");
  await page.getByLabel("Action type").selectOption("WEBHOOK");
  await expect(page.getByLabel("GitLab base URL")).not.toBeVisible();
  await page.getByLabel("Action type").selectOption("GITLAB_ISSUE");
  await page
    .getByLabel("Action name", { exact: true })
    .fill("<img src=x onerror=alert(1)>");
  await page.getByLabel("Project ID or path").fill("platform/browser-check");
  await page
    .getByLabel("GitLab access token", { exact: true })
    .fill("fictional-test-token");
  await page.getByLabel("Email mappings").fill("{invalid");
  await form
    .getByRole("button", { name: "Create action", exact: true })
    .click();
  await expect(page.locator("#action-error")).toBeVisible();
  await page.getByLabel("Email mappings").fill("{}");
  await page.route("**/api/v1/actions/gitlab/assignees/preview", (route) =>
    route.fulfill({
      json: {
        resolutions: [
          { email: "oncall@example.com", status: "MAPPED", gitlabUserId: 42 },
        ],
      },
    }),
  );
  await page.getByRole("button", { name: "Validate assignees" }).click();
  await expect(page.locator("#preview-result")).toContainText(
    "oncall@example.com: MAPPED",
  );
  await form
    .getByRole("button", { name: "Create action", exact: true })
    .click();
  await expect(page.locator("#action-dialog")).not.toBeVisible();
  await expect(page.locator("#action-list")).toContainText(
    "<img src=x onerror=alert(1)>",
  );
  await expect(page.locator("#action-list img")).toHaveCount(0);
});

test("GitHub, Forgejo, and Mattermost actions can be configured", async ({
  page,
}) => {
  await login(page);
  await page.goto("/actions");

  const open = async (type, name) => {
    await page
      .getByRole("button", { name: "Create action", exact: false })
      .click();
    await page.getByLabel("Action name", { exact: true }).fill(name);
    await page.getByLabel("Action type").selectOption(type);
  };
  const create = () =>
    page
      .locator("#action-form")
      .getByRole("button", { name: "Create action", exact: true })
      .click();

  await open("GITHUB_ISSUE", "Browser GitHub issues");
  await expect(page.getByLabel("GitLab base URL")).not.toBeVisible();
  await page
    .getByLabel("GitHub repository", { exact: true })
    .fill("acme/browser");
  await page
    .getByLabel("GitHub access token", { exact: true })
    .fill("github-secret");
  await page.getByLabel("Labels").fill("smtp\nproduction");
  await create();
  await expect(page.locator("#action-list")).toContainText(
    "Browser GitHub issues",
  );

  await open("FORGEJO_ISSUE", "Browser Forgejo issues");
  await page.getByLabel("Forgejo base URL").fill("https://forgejo.example.com");
  await page
    .getByLabel("Forgejo repository", { exact: true })
    .fill("acme/browser");
  await page
    .getByLabel("Forgejo access token", { exact: true })
    .fill("forgejo-secret");
  await page.getByLabel("Label IDs").fill("not-a-number");
  await create();
  await expect(page.locator("#action-error")).toContainText("positive numbers");
  await page.getByLabel("Label IDs").fill("7\n12");
  await create();
  await expect(page.locator("#action-list")).toContainText(
    "Browser Forgejo issues",
  );

  await open("MATTERMOST_MESSAGE", "Browser Mattermost");
  await page
    .getByLabel("Mattermost incoming webhook URL")
    .fill("https://mattermost.example.com/hooks/browser-secret");
  await page.getByLabel("Channel").fill("ops-alerts");
  await create();
  await expect(page.locator("#action-list")).toContainText(
    "Browser Mattermost",
  );

  const actions = await page.evaluate(() => window.smtp2x.api("/actions"));
  const github = actions.find(
    (action) => action.name === "Browser GitHub issues",
  );
  const forgejo = actions.find(
    (action) => action.name === "Browser Forgejo issues",
  );
  const mattermost = actions.find(
    (action) => action.name === "Browser Mattermost",
  );
  expect(github.configuration.accessToken).toBe("");
  expect(github.configuration.accessTokenConfigured).toBe(true);
  expect(forgejo.configuration.accessToken).toBe("");
  expect(forgejo.configuration.accessTokenConfigured).toBe(true);
  expect(mattermost.configuration.webhookUrl).toBe("");
  expect(mattermost.configuration.webhookUrlConfigured).toBe(true);
});
