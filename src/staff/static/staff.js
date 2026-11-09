// Small client-side helpers that replace the Swing dialogs and table state.
(function () {
  let leaving = false;

  // Buttons that only show a message ("Inventory view is manager only.")
  document.querySelectorAll("[data-alert]").forEach((el) =>
    el.addEventListener("click", () => alert(el.dataset.alert)));

  // Links that need a yes/no first
  document.querySelectorAll("[data-confirm-link]").forEach((el) =>
    el.addEventListener("click", (e) => {
      if (!confirm(el.dataset.confirmLink)) e.preventDefault();
    }));

  // --- Editable tables (Inventory amounts / Menu prices) -------------------
  const editForm = document.querySelector("form[data-editable]");
  const dirtyCount = () =>
    editForm ? editForm.querySelectorAll("tr.dirty").length : 0;

  if (editForm) {
    const label = editForm.querySelector(".unsaved-label");
    const total = editForm.querySelectorAll("tr[data-row]").length;
    const refresh = () => {
      const n = dirtyCount();
      label.textContent = n ? `${n} unsaved change${n === 1 ? "" : "s"}`
                            : `${total} ${editForm.dataset.noun}`;
      label.classList.toggle("error", n > 0);
      label.classList.toggle("muted", n === 0);
    };
    editForm.querySelectorAll("tr[data-row]").forEach((row) => {
      const input = row.querySelector("input[type=number]");
      const orig = row.querySelector("input[name^=orig_]").value;
      input.addEventListener("input", () => {
        const v = parseFloat(input.value);
        const dirty = !Number.isNaN(v) && v.toFixed(2) !== orig;
        row.classList.toggle("dirty", dirty);
        row.querySelector(".unsaved-cell").textContent = dirty ? "unsaved" : "";
        refresh();
      });
    });
    editForm.addEventListener("submit", (e) => {
      const n = dirtyCount();
      const msg = editForm.dataset.confirmSave;
      if (n && msg && !confirm(msg.replace("{n}", n))) { e.preventDefault(); return; }
      leaving = true;
    });
    // "Refresh" discards unsaved edits
    editForm.querySelectorAll("a.btn").forEach((a) =>
      a.addEventListener("click", (e) => {
        if (dirtyCount() && !confirm("Discard unsaved changes?")) e.preventDefault();
        else leaving = true;
      }));
    window.addEventListener("beforeunload", (e) => {
      if (!leaving && dirtyCount()) { e.preventDefault(); e.returnValue = ""; }
    });
  }

  // Forms that need a yes/no first (checkout, discontinue)
  document.querySelectorAll("form[data-confirm]").forEach((f) =>
    f.addEventListener("submit", (e) => {
      let msg = f.dataset.confirm;
      if (dirtyCount()) msg += "\n\nYour unsaved changes will be discarded.";
      if (!confirm(msg)) e.preventDefault();
      else leaving = true;
    }));

  // --- Reactivate: show preserved details of the selected item -------------
  const reForm = document.getElementById("reactivate-form");
  if (reForm) {
    const sel = reForm.querySelector("#item_id");
    const qty = reForm.querySelector("#quantity");
    const show = () => {
      const o = sel.selectedOptions[0];
      document.getElementById("r-unit").textContent = o.dataset.unit;
      document.getElementById("r-threshold").textContent = o.dataset.threshold;
      document.getElementById("r-shelf").textContent = `${o.dataset.shelf} days`;
    };
    sel.addEventListener("change", () => { show(); qty.value = ""; });
    show();
    reForm.addEventListener("submit", (e) => {
      const o = sel.selectedOptions[0];
      if (qty.value === "") return; // server reports the validation error
      if (!confirm(`Reactivate "${o.text}" with a starting quantity of ${qty.value} ${o.dataset.unit}?`))
        e.preventDefault();
    });
  }

  // --- Order history: item popup -------------------------------------------
  const dialog = document.getElementById("order-dialog");
  if (dialog) {
    const open = async (id) => {
      const res = await fetch(`/manager/orders/${id}.json`);
      const data = await res.json();
      if (!res.ok) { alert("Error loading order items: " + data.error); return; }
      document.getElementById("order-title").textContent = `Order #${id} Items`;
      const body = document.getElementById("order-items");
      body.replaceChildren(...data.items.map((it) => {
        const tr = document.createElement("tr");
        for (const [v, cls] of [[it.name, ""], [it.qty, "num"], [it.selections, ""]]) {
          const td = document.createElement("td");
          td.textContent = v ?? "";
          if (cls) td.className = cls;
          tr.append(td);
        }
        return tr;
      }));
      document.getElementById("order-empty").hidden = data.items.length > 0;
      dialog.showModal();
    };
    document.querySelectorAll("tr[data-order]").forEach((tr) => {
      tr.addEventListener("click", () => open(tr.dataset.order));
      tr.addEventListener("keydown", (e) => { if (e.key === "Enter") open(tr.dataset.order); });
    });
  }
})();
