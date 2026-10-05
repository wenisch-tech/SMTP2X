"use strict";
const $ = (s, root = document) => root.querySelector(s);
const $$ = (s, root = document) => [...root.querySelectorAll(s)];
const escape = (value) =>
  String(value ?? "").replace(
    /[&<>"']/g,
    (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        c
      ],
  );
const admin = document.body.dataset.admin === "true";
const TYPE_NAMES = {
  GITLAB_ISSUE: "GitLab issue",
  GITHUB_ISSUE: "GitHub issue",
  FORGEJO_ISSUE: "Forgejo issue",
  MATTERMOST_MESSAGE: "Mattermost message",
  WEBHOOK: "Webhook",
};
const typeName = (type) => TYPE_NAMES[type] || type;
const svgIcon = (name) =>
  `<svg class="icon" aria-hidden="true"><use href="/css/icons.svg#${name}"></use></svg>`;
const TYPE_ICONS = {
  GITLAB_ISSUE: "gitlab",
  GITHUB_ISSUE: "github",
  FORGEJO_ISSUE: "forgejo",
  MATTERMOST_MESSAGE: "mattermost",
  WEBHOOK: "webhook",
};
const icon = (type) => svgIcon(TYPE_ICONS[type] || "action");
const instant = (value) =>
  new Date(typeof value === "number" ? value * 1000 : value);
const date = (value) =>
  value
    ? instant(value).toLocaleString(undefined, {
        month: "short",
        day: "numeric",
        hour: "2-digit",
        minute: "2-digit",
      })
    : "—";
const state = (enabled) =>
  `<span class="badge ${enabled ? "good" : ""}">${enabled ? "Enabled" : "Disabled"}</span>`;
const statusBadge = (status, label = status) =>
  `<span class="badge ${status === "SUCCEEDED" ? "good" : status === "FAILED" ? "bad" : status === "PENDING" || status === "RUNNING" ? "warn" : ""}">${escape(label.toLowerCase().replaceAll("_", " "))}</span>`;
const empty = (title, description = "") =>
  `<div class="empty"><h3>${escape(title)}</h3>${escape(description)}</div>`;
function notice(message, error = false, target = "#page-notice") {
  const node = $(target);
  if (!node) return;
  node.textContent = message;
  node.hidden = !message;
  node.classList.toggle("error", error);
}
async function api(path, options = {}) {
  const token = $('meta[name="_csrf"]')?.content;
  const response = await fetch("/api/v1" + path, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { "X-CSRF-TOKEN": token } : {}),
      ...options.headers,
    },
  });
  if (response.redirected && new URL(response.url).pathname === "/login")
    throw new Error("Your session expired. Sign in again to continue.");
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(
      body.error ||
        (response.status === 403
          ? "You do not have permission for this action."
          : `Request failed (${response.status}). Please try again.`),
    );
  }
  if (response.status === 204) return null;
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}
window.smtp2x = { api };
function destination(action) {
  if (action.destination) return action.destination;
  if (action.type === "MATTERMOST_MESSAGE")
    return "Mattermost incoming webhook";
  try {
    const c = action.configuration;
    const issue = ["GITLAB_ISSUE", "GITHUB_ISSUE", "FORGEJO_ISSUE"].includes(
      action.type,
    );
    const url = new URL(issue ? c.baseUrl : c.url);
    const target =
      action.type === "GITLAB_ISSUE"
        ? c.project
        : ["GITHUB_ISSUE", "FORGEJO_ISSUE"].includes(action.type)
          ? c.repository
          : "";
    return url.hostname + (target ? " · " + target : "");
  } catch {
    return "Destination configured";
  }
}
function conditions(rule) {
  return [
    rule.globalRule ? "All recipients" : rule.recipientPattern,
    rule.senderPattern ? "From " + rule.senderPattern : "",
    rule.subjectFilter
      ? `Subject ${rule.subjectMode === "EQUALS" ? "equals" : "contains"} “${rule.subjectFilter}”`
      : "",
  ]
    .filter(Boolean)
    .join(" · ");
}
async function busy(button, work) {
  button.disabled = true;
  try {
    return await work();
  } finally {
    button.disabled = false;
  }
}

// The same action dialog is used on the Actions page and within a rule draft.
function actionDialog(onSaved) {
  const dialog = $("#action-dialog");
  if (!dialog) return;
  const form = $("#action-form"),
    field = (name) => form.elements.namedItem(name);
  let editing = null;
  const secretFields = [
    ["accessToken", "accessTokenConfigured", true],
    ["githubAccessToken", "accessTokenConfigured", true],
    ["forgejoAccessToken", "accessTokenConfigured", true],
    ["mattermostWebhookUrl", "webhookUrlConfigured", true],
    ["bearerToken", "bearerTokenConfigured", false],
  ];
  secretFields.forEach(([name]) => {
    field(name).dataset.defaultPlaceholder = field(name).placeholder;
  });
  const sync = () => {
    const type = field("type").value;
    $$("[data-action-fields]", form).forEach((fields) => {
      const active = fields.dataset.actionFields === type;
      fields.hidden = !active;
      fields.disabled = !active;
    });
    $("#preview-result").textContent = "";
    notice("", false, "#action-error");
  };
  const set = (name, value) => {
    if (value !== undefined && value !== null) field(name).value = value;
  };
  const setLines = (name, values) => {
    if (Array.isArray(values)) field(name).value = values.join("\n");
  };
  const configureSecrets = (configuration = {}) => {
    secretFields.forEach(([name, configuredProperty, required]) => {
      const input = field(name);
      const configured = Boolean(editing && configuration[configuredProperty]);
      input.value = "";
      input.required = required && !configured;
      input.placeholder = configured
        ? "Configured — leave blank to keep it"
        : input.dataset.defaultPlaceholder;
    });
  };
  const populate = (action) => {
    const c = action.configuration || {};
    field("name").value = action.name;
    field("type").value = action.type;
    field("enabled").checked = action.enabled;
    field("ignoreTlsErrors").checked = Boolean(c.ignoreTlsErrors);
    switch (action.type) {
      case "GITLAB_ISSUE":
        set("baseUrl", c.baseUrl);
        set("project", c.project);
        set("gitlabTitleTemplate", c.titleTemplate);
        set("gitlabDescriptionTemplate", c.descriptionTemplate);
        field("gitlabUploadAttachments").checked =
          c.uploadAttachments !== false;
        field("useRecipient").checked = Boolean(c.useRecipient);
        setLines("defaults", c.defaultAssigneeEmails);
        if (c.assigneeEmailMappings !== undefined)
          field("mappings").value = JSON.stringify(
            c.assigneeEmailMappings,
            null,
            2,
          );
        set("gitlabAutoDeleteAfter", c.autoDeleteAfter);
        break;
      case "GITHUB_ISSUE":
        set("githubBaseUrl", c.baseUrl);
        set("githubRepository", c.repository);
        set("githubTitleTemplate", c.titleTemplate);
        set("githubBodyTemplate", c.bodyTemplate);
        setLines("githubLabels", c.labels);
        setLines("githubAssignees", c.assignees);
        break;
      case "FORGEJO_ISSUE":
        set("forgejoBaseUrl", c.baseUrl);
        set("forgejoRepository", c.repository);
        set("forgejoTitleTemplate", c.titleTemplate);
        set("forgejoBodyTemplate", c.bodyTemplate);
        field("forgejoUploadAttachments").checked =
          c.uploadAttachments !== false;
        setLines("forgejoLabelIds", c.labelIds?.map(String));
        setLines("forgejoAssignees", c.assignees);
        set("forgejoAutoDeleteAfter", c.autoDeleteAfter);
        break;
      case "MATTERMOST_MESSAGE":
        set("mattermostTextTemplate", c.textTemplate);
        set("mattermostChannel", c.channel);
        set("mattermostUsername", c.username);
        set("mattermostIconUrl", c.iconUrl);
        break;
      default:
        set("url", c.url);
    }
    configureSecrets(c);
  };
  const lines = (name) =>
    field(name)
      .value.split("\n")
      .map((value) => value.trim())
      .filter(Boolean);
  const configuration = () => {
    const common = { ignoreTlsErrors: field("ignoreTlsErrors").checked };
    switch (field("type").value) {
      case "GITLAB_ISSUE":
        return {
          ...common,
          baseUrl: field("baseUrl").value,
          project: field("project").value,
          accessToken: field("accessToken").value,
          titleTemplate: field("gitlabTitleTemplate").value,
          descriptionTemplate: field("gitlabDescriptionTemplate").value,
          uploadAttachments: field("gitlabUploadAttachments").checked,
          useRecipient: field("useRecipient").checked,
          defaultAssigneeEmails: lines("defaults"),
          assigneeEmailMappings: JSON.parse(field("mappings").value || "{}"),
          autoDeleteAfter: field("gitlabAutoDeleteAfter").value.trim(),
        };
      case "GITHUB_ISSUE":
        return {
          ...common,
          baseUrl: field("githubBaseUrl").value,
          repository: field("githubRepository").value,
          accessToken: field("githubAccessToken").value,
          titleTemplate: field("githubTitleTemplate").value,
          bodyTemplate: field("githubBodyTemplate").value,
          labels: lines("githubLabels"),
          assignees: lines("githubAssignees"),
        };
      case "FORGEJO_ISSUE": {
        const labelIds = lines("forgejoLabelIds").map(Number);
        if (
          labelIds.some((value) => !Number.isSafeInteger(value) || value <= 0)
        )
          throw new Error(
            "Forgejo label IDs must be positive numbers, one per line.",
          );
        return {
          ...common,
          baseUrl: field("forgejoBaseUrl").value,
          repository: field("forgejoRepository").value,
          accessToken: field("forgejoAccessToken").value,
          titleTemplate: field("forgejoTitleTemplate").value,
          bodyTemplate: field("forgejoBodyTemplate").value,
          uploadAttachments: field("forgejoUploadAttachments").checked,
          labelIds,
          assignees: lines("forgejoAssignees"),
          autoDeleteAfter: field("forgejoAutoDeleteAfter").value.trim(),
        };
      }
      case "MATTERMOST_MESSAGE":
        return {
          ...common,
          webhookUrl: field("mattermostWebhookUrl").value,
          textTemplate: field("mattermostTextTemplate").value,
          channel: field("mattermostChannel").value,
          username: field("mattermostUsername").value,
          iconUrl: field("mattermostIconUrl").value,
        };
      default:
        return {
          ...common,
          url: field("url").value,
          bearerToken: field("bearerToken").value,
        };
    }
  };
  field("type").addEventListener("change", sync);
  ["#close-action", "#cancel-action"].forEach(
    (s) => ($(s).onclick = () => dialog.close()),
  );
  $("#preview-assignees").onclick = () =>
    busy($("#preview-assignees"), async () => {
      try {
        if (editing && !field("accessToken").value)
          throw new Error(
            "Enter a new GitLab access token to validate assignees. Leaving it blank keeps the configured token when saving.",
          );
        const result = await api("/actions/gitlab/assignees/preview", {
          method: "POST",
          body: JSON.stringify({
            configuration: configuration(),
            recipients: [],
          }),
        });
        $("#preview-result").textContent =
          result.resolutions
            .map((x) => `${x.email}: ${x.status}`)
            .join(" · ") || "No assignees configured";
      } catch (e) {
        notice(e.message, true, "#action-error");
      }
    });
  form.onsubmit = (e) => {
    e.preventDefault();
    busy($('button[type="submit"],button:not([type])', form), async () => {
      try {
        notice("", false, "#action-error");
        const action = await api(
          "/actions" + (editing ? "/" + editing.id : ""),
          {
            method: editing ? "PUT" : "POST",
            body: JSON.stringify({
              name: field("name").value,
              type: field("type").value,
              enabled: field("enabled").checked,
              configuration: configuration(),
            }),
          },
        );
        dialog.close();
        await onSaved(action, Boolean(editing));
      } catch (e) {
        notice(e.message, true, dialog.open ? "#action-error" : "#page-notice");
      }
    });
  };
  return (action = null) => {
    editing = action;
    field("type").disabled = false;
    form.reset();
    if (action) populate(action);
    else configureSecrets();
    sync();
    field("type").disabled = Boolean(action);
    $("#action-dialog-eyebrow").textContent = action
      ? "UPDATE A DESTINATION"
      : "CONNECT A DESTINATION";
    $("#action-dialog-title").textContent = action
      ? "Edit action"
      : "Create action";
    $("#save-action").textContent = action ? "Save action" : "Create action";
    dialog.showModal();
    field("name").focus();
  };
}

async function rulesPage() {
  let [rules, actions] = await Promise.all([api("/rules"), api("/actions")]);
  let editing = null,
    selected = new Set(),
    original = new Set();
  const form = $("#rule-form"),
    field = (n) => form.elements.namedItem(n);
  const actionById = () => new Map(actions.map((a) => [a.id, a]));
  function renderRules() {
    const query = $("#rule-search").value.toLowerCase();
    $("#rule-list").innerHTML =
      rules
        .filter((r) =>
          [
            r.name,
            conditions(r),
            ...r.actionIds.map((id) => actionById().get(id)?.name || ""),
          ]
            .join(" ")
            .toLowerCase()
            .includes(query),
        )
        .map(
          (r) => `
      <article class="card rule-card ${editing === r.id ? "current" : ""}"><div class="rule-heading"><h2>${escape(r.name)}</h2>${state(r.enabled)}</div><p class="rule-condition">${escape(conditions(r))}</p><div class="action-chips">${r.actionIds
        .map((id) => {
          const a = actionById().get(id);
          return `<span class="action-chip">${a ? icon(a.type) : "!"} ${escape(a?.name || "Unavailable action")}${a && !a.enabled ? " · Disabled" : ""}</span>`;
        })
        .join(
          "",
        )}</div><div class="rule-footer"><span>${r.globalRule ? "GLOBAL ROUTE" : "RECIPIENT ROUTE"} · ${r.actionIds.length} action${r.actionIds.length === 1 ? "" : "s"}</span>${admin ? `<button class="text-button" data-edit="${r.id}">Edit rule ↗</button>` : ""}</div></article>`,
        )
        .join("") ||
      empty(
        rules.length ? "No matching rules" : "Give your messages a direction",
        rules.length
          ? "Try a different search."
          : "Create a rule and choose the actions it should run.",
      );
    $$("[data-edit]").forEach(
      (b) => (b.onclick = () => openRule(b.dataset.edit)),
    );
  }
  function renderOptions() {
    const query = $("#action-search").value.toLowerCase();
    const available = [
      ...actions,
      ...[...selected]
        .filter((id) => !actionById().has(id))
        .map((id) => ({
          id,
          name: "Unavailable action",
          enabled: false,
          missing: true,
        })),
    ];
    $("#action-options").innerHTML =
      available
        .filter((a) =>
          [a.name, a.type, destination(a)]
            .join(" ")
            .toLowerCase()
            .includes(query),
        )
        .map(
          (a) =>
            `<label class="action-option"><input type="checkbox" value="${escape(a.id)}" ${selected.has(a.id) ? "checked" : ""} ${!a.enabled && !original.has(a.id) && !selected.has(a.id) ? "disabled" : ""}><span>${escape(a.name)}${a.enabled ? "" : ` <span class="badge ${a.missing ? "bad" : ""}">${a.missing ? "Remove reference" : "Disabled"}</span>`}<small>${a.missing ? "This action no longer exists." : escape(typeName(a.type) + " · " + destination(a))}</small></span></label>`,
        )
        .join("") ||
      empty(
        actions.length ? "No matching actions" : "No actions yet",
        "Create an action to connect a destination.",
      );
    $$("#action-options input").forEach(
      (input) =>
        (input.onchange = () => {
          if (input.checked) selected.add(input.value);
          else selected.delete(input.value);
        }),
    );
  }
  function syncScope() {
    field("recipientPattern").disabled = field("globalRule").checked;
    field("recipientPattern").required = !field("globalRule").checked;
    $("#recipient-label").hidden = field("globalRule").checked;
  }
  function openRule(id) {
    editing = id || null;
    const rule = rules.find((r) => r.id === id);
    form.reset();
    selected = new Set(rule?.actionIds || []);
    original = new Set(selected);
    for (const name of [
      "name",
      "recipientPattern",
      "senderPattern",
      "subjectFilter",
      "subjectMode",
    ])
      if (rule) field(name).value = rule[name] ?? "";
    field("enabled").checked = rule?.enabled ?? true;
    field("globalRule").checked = rule?.globalRule ?? false;
    $("#editor-title").textContent = rule ? "Edit rule" : "Create rule";
    $("#action-search").value = "";
    notice("", false, "#rule-error");
    $("#rule-editor").hidden = false;
    $("#rules-workspace").classList.add("editing");
    syncScope();
    renderOptions();
    renderRules();
    field("name").focus({ preventScroll: true });
    if (innerWidth < 1150)
      $("#rule-editor").scrollIntoView({ behavior: "smooth" });
  }
  function closeRule() {
    editing = null;
    $("#rule-editor").hidden = true;
    $("#rules-workspace").classList.remove("editing");
    history.replaceState(null, "", "/rules");
    renderRules();
    $("#new-rule").focus();
  }
  $("#rule-search").oninput = renderRules;
  renderRules();
  if (!admin) return;
  const openAction = actionDialog(async (action) => {
    actions.push(action);
    selected.add(action.id);
    renderOptions();
    notice("Action created and selected. Save the rule to connect it.");
  });
  $("#new-rule").onclick = () => openRule();
  $("#close-rule").onclick = closeRule;
  $("#cancel-rule").onclick = closeRule;
  $("#inline-action").onclick = () => openAction();
  $("#action-search").oninput = renderOptions;
  field("globalRule").onchange = syncScope;
  form.onsubmit = (e) => {
    e.preventDefault();
    busy($("#save-rule"), async () => {
      try {
        if (!selected.size)
          throw new Error("Select at least one action, or create one first.");
        const request = {
          name: field("name").value,
          enabled: field("enabled").checked,
          globalRule: field("globalRule").checked,
          recipientPattern: field("recipientPattern").value,
          senderPattern: field("senderPattern").value,
          subjectFilter: field("subjectFilter").value,
          subjectMode: field("subjectMode").value,
          actionIds: [...selected],
        };
        const saved = await api("/rules" + (editing ? "/" + editing : ""), {
          method: editing ? "PUT" : "POST",
          body: JSON.stringify(request),
        });
        rules = rules.filter((r) => r.id !== saved.id);
        rules.push(saved);
        closeRule();
        notice("Rule saved. Your routing configuration is up to date.");
      } catch (e) {
        notice(e.message, true, "#rule-error");
      }
    });
  };
  const id = new URLSearchParams(location.search).get("edit");
  if (id && rules.some((r) => r.id === id)) openRule(id);
}

async function actionsPage() {
  let [actions, rules] = await Promise.all([api("/actions"), api("/rules")]);
  function render() {
    $("#action-list").innerHTML =
      actions
        .map(
          (a) =>
            `<article class="card" id="action-${a.id}"><div class="section-heading"><div class="node-header"><span class="node-icon ${a.type.toLowerCase()}">${icon(a.type)}</span><h2>${escape(a.name)}</h2></div><div class="action-card-controls">${state(a.enabled)}${admin ? `<button class="text-button" data-edit-action="${a.id}">Edit action ↗</button>` : ""}</div></div><span class="badge blue">${typeName(a.type)}</span><p class="action-destination">${escape(destination(a))}</p><div class="linked-rules"><span class="muted small">USED BY ROUTING RULES</span><div>${
              rules
                .filter((r) => r.actionIds.includes(a.id))
                .map(
                  (r) =>
                    `<a href="/rules${admin ? "?edit=" + r.id : ""}">${escape(r.name)} ↗</a>`,
                )
                .join("") ||
              '<span class="muted">Not connected to a rule yet.</span>'
            }</div></div></article>`,
        )
        .join("") ||
      empty(
        "Connect your first destination",
        "Create an issue, Mattermost, or webhook action, then attach it to a rule.",
      );
    $$('[data-edit-action]').forEach(
      (button) =>
        (button.onclick = () =>
          openAction(
            actions.find(
              (action) => action.id === button.dataset.editAction,
            ),
          )),
    );
  }
  const openAction = admin
    ? actionDialog(async (saved, wasEditing) => {
        actions = actions.filter((action) => action.id !== saved.id);
        actions.push(saved);
        render();
        notice(
          wasEditing
            ? "Action saved. Existing credentials were kept unless you replaced them."
            : "Action created. You can now select it in a routing rule.",
        );
      })
    : null;
  render();
  if (admin) $("#new-action").onclick = () => openAction();
  if (location.hash)
    document.getElementById(location.hash.slice(1))?.scrollIntoView();
}

async function dashboardPage() {
  let data,
    selection = null,
    loading = false;
  const key = (kind, id) => kind + ":" + id;
  const findNode = (k) =>
    k?.startsWith("rule:")
      ? data.rules.find((r) => key("rule", r.id) === k)
      : data.actions.find((a) => key("action", a.id) === k);
  function node(kind, item) {
    const isRule = kind === "rule";
    return `<button class="flow-node ${item.enabled ? "" : "disabled"}" data-node="${key(kind, item.id)}" aria-pressed="false"><div class="node-header"><span class="node-icon ${!isRule && item.type ? item.type.toLowerCase() : ""}" aria-hidden="true">${isRule ? svgIcon("flow") : icon(item.type)}</span><span class="node-name">${escape(item.name)}</span></div><div class="node-sub">${escape(isRule ? conditions(item) : item.missing ? "Remove this reference in the rule editor." : typeName(item.type) + " · " + destination(item))}</div><div class="node-state"><i class="dot ${item.enabled ? "" : "gray"}"></i>${item.missing ? "Unavailable action" : item.enabled ? "Enabled" : "Disabled"}</div></button>`;
  }
  function renderFlow() {
    const missing = [...new Set(data.rules.flatMap((r) => r.actionIds))]
      .filter((id) => !data.actions.some((a) => a.id === id))
      .map((id) => ({
        id,
        name: "Unavailable action",
        enabled: false,
        missing: true,
      }));
    data.actions.push(...missing);
    if (!data.rules.length) {
      $("#flow").innerHTML =
        empty(
          "Build your first route",
          "Connect incoming email to an issue, Mattermost message, or webhook.",
        ) +
        (admin
          ? '<div style="text-align:center"><a class="btn" href="/rules">Create a routing rule ↗</a></div>'
          : "");
      $("#flow-detail").hidden = true;
      return;
    }
    $("#flow").innerHTML =
      `<div class="flow-scroll"><div class="flow-canvas"><svg class="flow-wires" aria-hidden="true"></svg><div class="flow-column source"><div class="flow-node source-node" data-source><span class="node-icon">${svgIcon("mail")}</span><div class="node-name">Incoming email</div><div class="node-sub">SMTP notification gateway</div><div class="node-state"><span class="badge blue">SMTP → automation</span></div></div></div><div class="flow-column"><div class="column-label">MATCHING RULES <span>${data.rules.length}</span></div>${data.rules.map((r) => node("rule", r)).join("")}</div><div class="flow-column"><div class="column-label">ACTIONS <span>${data.actions.length}</span></div>${data.actions.map((a) => node("action", a)).join("")}</div></div></div><div class="mobile-routes">${data.rules
        .map(
          (r) =>
            `<div class="mobile-route"><div class="eyebrow">INCOMING EMAIL → RULE</div>${node("rule", r)}<div class="eyebrow">THEN RUN</div>${r.actionIds
              .map((id) =>
                node(
                  "action",
                  data.actions.find((a) => a.id === id),
                ),
              )
              .join("")}</div>`,
        )
        .join("")}</div>`;
    $$("[data-node]", $("#flow")).forEach(
      (b) =>
        (b.onclick = () => {
          selection = selection === b.dataset.node ? null : b.dataset.node;
          highlight();
        }),
    );
    if (selection && !findNode(selection)) selection = null;
    highlight();
    requestAnimationFrame(draw);
  }
  function connected() {
    const result = new Set(selection ? [selection] : []);
    if (selection?.startsWith("rule:"))
      findNode(selection)?.actionIds.forEach((id) =>
        result.add(key("action", id)),
      );
    if (selection?.startsWith("action:"))
      data.rules
        .filter((r) =>
          r.actionIds.some((id) => key("action", id) === selection),
        )
        .forEach((r) => result.add(key("rule", r.id)));
    return result;
  }
  function highlight() {
    const paths = connected();
    $$("[data-node]", $("#flow")).forEach((b) => {
      b.classList.toggle("selected", b.dataset.node === selection);
      b.classList.toggle("connected", paths.has(b.dataset.node));
      b.setAttribute("aria-pressed", String(b.dataset.node === selection));
    });
    const item = findNode(selection),
      detail = $("#flow-detail");
    detail.hidden = !item;
    if (item) {
      const isRule = selection.startsWith("rule:");
      detail.className = "flow-detail";
      detail.innerHTML = `<div><h3>${escape(item.name)}</h3><p class="muted">${escape(isRule ? conditions(item) : item.missing ? "This action is unavailable. Edit the connected rule to remove it." : typeName(item.type) + " · " + destination(item))}</p><p class="muted">${isRule ? item.actionIds.length + " connected action(s)" : data.rules.filter((r) => r.actionIds.includes(item.id)).length + " connected rule(s)"} · ${item.enabled ? "Enabled" : "Disabled"}</p></div>${!item.missing ? `<a class="btn secondary compact" href="${isRule ? "/rules" + (admin ? "?edit=" + item.id : "") : "/actions#action-" + item.id}">${isRule && admin ? "Edit rule" : isRule ? "View rules" : "View action"} ↗</a>` : ""}`;
    }
    draw();
  }
  function draw() {
    const canvas = $(".flow-canvas"),
      svg = $(".flow-wires");
    if (!canvas || !canvas.offsetWidth) return;
    const rect = canvas.getBoundingClientRect(),
      source = $("[data-source]", canvas),
      paths = [];
    function edge(from, to, active, inactive) {
      if (!from || !to) return;
      const a = from.getBoundingClientRect(),
        b = to.getBoundingClientRect();
      const x1 = a.right - rect.left,
        y1 = a.top + a.height / 2 - rect.top,
        x2 = b.left - rect.left,
        y2 = b.top + b.height / 2 - rect.top,
        mid = (x1 + x2) / 2;
      paths.push(
        `<path class="${active ? "highlight" : ""} ${inactive ? "inactive" : ""}" d="M ${x1} ${y1} C ${mid} ${y1}, ${mid} ${y2}, ${x2} ${y2}"/>`,
      );
    }
    const lookup = (k) =>
        $$("[data-node]", canvas).find((n) => n.dataset.node === k),
      related = connected();
    data.rules.forEach((r) => {
      edge(
        source,
        lookup(key("rule", r.id)),
        related.has(key("rule", r.id)),
        !r.enabled,
      );
      r.actionIds.forEach((id) =>
        edge(
          lookup(key("rule", r.id)),
          lookup(key("action", id)),
          related.has(key("rule", r.id)) && related.has(key("action", id)),
          !r.enabled || !data.actions.find((a) => a.id === id)?.enabled,
        ),
      );
    });
    svg.innerHTML = paths.join("");
  }
  function render() {
    const c = data.counts;
    $("#metrics").innerHTML = [
      ["Retained messages", c.messages, "Received & stored", "✉", ""],
      ["In progress", c.active, "Pending + running", "↗", "amber"],
      [
        "Successful deliveries",
        c.succeeded,
        "Delivered to destination",
        "✓",
        "green",
      ],
      ["Failed deliveries", c.failed, "May need your attention", "!", "red"],
    ]
      .map(
        ([name, value, note, symbol, color]) =>
          `<article class="card metric-card"><div><div class="metric-label">${name}</div><div class="metric-number">${Number(value).toLocaleString()}</div><div class="metric-note">${note}</div></div><span class="metric-icon ${color}" aria-hidden="true">${svgIcon({ "✉": "mail", "↗": "arrow", "✓": "check", "!": "alert" }[symbol])}</span></article>`,
      )
      .join("");
    $("#recent-messages").innerHTML =
      data.recentMessages
        .map(
          (m) =>
            `<a class="activity-row" href="/messages#message-${m.id}"><span class="activity-icon">${svgIcon("mail")}</span><div class="activity-content"><div class="activity-title">${escape(m.subject)}</div><div class="activity-meta">${escape(m.envelopeFrom)}</div></div><span class="activity-time">${escape(date(m.receivedAt))}</span></a>`,
        )
        .join("") ||
      empty("No messages yet", "Accepted messages will appear here.");
    $("#recent-failures").innerHTML =
      data.recentFailures
        .map(
          (f) =>
            `<a class="activity-row" href="/deliveries#delivery-${f.id}"><span class="activity-icon">${svgIcon("alert")}</span><div class="activity-content"><div class="activity-title">${escape(f.actionName)}</div><div class="activity-meta">${f.attempts} failed attempt${f.attempts === 1 ? "" : "s"} · ${escape(date(f.updatedAt))}</div></div><span class="badge bad">Failed</span></a>`,
        )
        .join("") ||
      empty("All clear", "No failed deliveries in retained records.");
    renderFlow();
    $("#updated").textContent =
      "Updated " +
      instant(data.updatedAt).toLocaleTimeString(undefined, {
        hour: "2-digit",
        minute: "2-digit",
      });
  }
  async function refresh() {
    if (loading) return;
    loading = true;
    $("#refresh").disabled = true;
    const focused = document.activeElement?.dataset.node;
    try {
      data = await api("/dashboard");
      render();
      notice("");
      if (focused)
        $$("[data-node]")
          .find((n) => n.dataset.node === focused && n.offsetParent)
          ?.focus({ preventScroll: true });
    } catch (e) {
      notice((data ? "Showing last available data. " : "") + e.message, true);
      if (!data)
        $("#metrics").innerHTML = empty(
          "Workspace unavailable",
          "Use Refresh to try again.",
        );
    } finally {
      loading = false;
      $("#refresh").disabled = false;
    }
  }
  $("#refresh").onclick = refresh;
  new ResizeObserver(draw).observe($("#flow"));
  setInterval(() => {
    if (!document.hidden) refresh();
  }, 30000);
  document.addEventListener("visibilitychange", () => {
    if (!document.hidden) refresh();
  });
  await refresh();
}

async function historyPage(page) {
  let values;
  let names = new Map(),
    cleanupByDelivery = new Map();
  if (page === "deliveries") {
    const [deliveries, actions, cleanups] = await Promise.all([
      api("/deliveries"),
      api("/actions"),
      api("/cleanups"),
    ]);
    values = deliveries;
    names = new Map(actions.map((a) => [a.id, a.name]));
    cleanupByDelivery = new Map(cleanups.map((c) => [c.deliveryId, c]));
  } else values = await api("/" + page);
  function remote(url) {
    try {
      const u = new URL(url);
      return ["http:", "https:"].includes(u.protocol)
        ? `<a href="${escape(u.href)}" target="_blank" rel="noopener noreferrer">Open destination ↗</a>`
        : "";
    } catch {
      return "";
    }
  }
  function cleanup(value) {
    if (!value) return '<span class="muted">Kept</span>';
    const labels = {
      PENDING: "Scheduled",
      RUNNING: "Deleting",
      SUCCEEDED: "Deleted",
      FAILED: "Failed",
    };
    const detail =
      value.status === "PENDING"
        ? `Due ${date(value.dueAt)}`
        : value.lastError || "Cleanup complete";
    return `${statusBadge(value.status, labels[value.status] || value.status)}<small>${escape(detail)}</small>`;
  }
  $("#history-rows").innerHTML = values
    .map((v) => {
      if (page === "messages") {
        let recipients = [];
        try {
          recipients = JSON.parse(v.recipientsJson);
        } catch {}
        return `<tr id="message-${v.id}"><td>${escape(date(v.receivedAt))}</td><td>${escape(v.envelopeFrom)}<small>To ${escape(recipients.join(", "))}</small></td><td>${escape(v.subject)}</td></tr>`;
      }
      if (page === "deliveries")
        return `<tr id="delivery-${v.id}"><td>${statusBadge(v.status)}</td><td>${escape(names.get(v.actionId) || "Unavailable action")}</td><td>${v.attempts}</td><td>${cleanup(cleanupByDelivery.get(v.id))}</td><td>${remote(v.remoteUrl)}<small>${escape(v.warnings || v.diagnostics || "—")}</small></td></tr>`;
      return `<tr><td>${escape(date(v.occurredAt))}</td><td>${escape(v.actor)}</td><td>${escape(v.eventType.toLowerCase().replaceAll("_", " "))}</td><td>${escape(v.detail)}</td></tr>`;
    })
    .join("");
  $("#history-empty").hidden = !!values.length;
  if (location.hash)
    document.getElementById(location.hash.slice(1))?.scrollIntoView();
}

async function administrationPage() {
  let passwordUser;
  const dialog = $("#password-dialog"),
    passwordForm = $("#password-form");
  $("#cancel-password").onclick = () => dialog.close();
  passwordForm.onsubmit = (e) => {
    e.preventDefault();
    busy($("button:not([type])", passwordForm), async () => {
      try {
        await api("/users/" + passwordUser + "/password", {
          method: "PUT",
          body: JSON.stringify({
            password: passwordForm.elements.namedItem("password").value,
          }),
        });
        dialog.close();
        notice("Password updated.");
      } catch (e) {
        notice(e.message, true, "#password-error");
      }
    });
  };
  async function refresh() {
    const users = await api("/users");
    $("#users").innerHTML = users
      .map(
        (u) =>
          `<tr><td>${escape(u.email)}</td><td><select aria-label="Role for ${escape(u.email)}" data-role="${u.id}">${["PENDING", "VIEWER", "ADMIN"].map((r) => `<option ${u.role === r ? "selected" : ""}>${r}</option>`).join("")}</select></td><td>${u.oidcOnly ? '<span class="muted">OIDC only</span>' : `<button class="btn secondary compact" data-password="${u.id}">Change password</button>`}</td><td>${state(u.enabled)}</td></tr>`,
      )
      .join("");
    $$("[data-role]").forEach(
      (s) =>
        (s.onchange = async () => {
          try {
            await api("/users/" + s.dataset.role + "/role", {
              method: "PUT",
              body: JSON.stringify({ role: s.value }),
            });
            notice("Role updated.");
          } catch (e) {
            notice(e.message, true);
          }
          await refresh();
        }),
    );
    $$("[data-password]").forEach(
      (b) =>
        (b.onclick = () => {
          passwordUser = b.dataset.password;
          passwordForm.reset();
          notice("", false, "#password-error");
          dialog.showModal();
        }),
    );
  }
  $("#new-user").onsubmit = (e) => {
    e.preventDefault();
    const f = e.currentTarget;
    busy($("button", f), async () => {
      try {
        await api("/users", {
          method: "POST",
          body: JSON.stringify({
            email: f.elements.namedItem("email").value,
            password: f.elements.namedItem("password").value,
            role: f.elements.namedItem("role").value,
          }),
        });
        f.reset();
        await refresh();
        notice("User created.");
      } catch (e) {
        notice(e.message, true);
      }
    });
  };
  await refresh();
}
const page = document.body.dataset.page;
const handlers = {
  dashboard: dashboardPage,
  rules: rulesPage,
  actions: actionsPage,
  administration: administrationPage,
  messages: () => historyPage("messages"),
  deliveries: () => historyPage("deliveries"),
  audit: () => historyPage("audit"),
};
if (handlers[page]) {
  const main = $("main");
  main.setAttribute("aria-busy", "true");
  const list = $("#rule-list, #action-list");
  if (list)
    list.innerHTML =
      '<div class="card muted" role="status">Loading configuration…</div>';
  const table = $("#history-rows, #users");
  if (table)
    table.innerHTML =
      '<tr><td colspan="4" class="muted">Loading activity…</td></tr>';
  handlers[page]()
    .catch((e) => {
      if (list) list.innerHTML = "";
      if (table) table.innerHTML = "";
      notice(e.message, true);
    })
    .finally(() => main.setAttribute("aria-busy", "false"));
}
