'use strict';

const $ = id => document.getElementById(id);
const currency = value => new Intl.NumberFormat('en-US', {style: 'currency', currency: 'USD'}).format(value);
const title = text => text.toLowerCase().replace(/\b\w/g, c => c.toUpperCase());
const node = (tag, text, className) => {
  const element = document.createElement(tag);
  if (text !== undefined) element.textContent = text;
  if (className) element.className = className;
  return element;
};
function saved(key, fallback) {
  try { return JSON.parse(localStorage.getItem(key)) ?? fallback; }
  catch { return fallback; }
}
const state = {items: [], cart: saved('panda-cart', []), category: 'Meals', quote: null,
  customer: null, guest: false, revision: 0, pending: saved('panda-checkout', null), busy: false};
if (!Array.isArray(state.cart)) state.cart = [];
let csrf = document.querySelector('meta[name="csrf-token"]').content;

async function api(path, body) {
  const options = body === undefined ? {} : {method: 'POST', headers: {'Content-Type': 'application/json', 'X-CSRF-Token': csrf}, body: JSON.stringify(body)};
  const response = await fetch(path, options);
  const result = await response.json();
  if (!response.ok) {
    const error = new Error(result.error || 'Something went wrong. Please try again.');
    error.status = response.status;
    throw error;
  }
  return result;
}
function persist() {
  try {
    localStorage.setItem('panda-cart', JSON.stringify(state.cart));
    if (state.pending) localStorage.setItem('panda-checkout', JSON.stringify(state.pending));
    else localStorage.removeItem('panda-checkout');
  } catch { /* The order still works when browser storage is unavailable. */ }
}
function photo(item, className) {
  if (!item.image) return node('div', 'Photo coming soon', className || 'photo-placeholder');
  const img = node('img');
  img.src = '/images/' + encodeURIComponent(item.image);
  img.alt = title(item.name);
  img.loading = 'lazy';
  img.addEventListener('error', () => img.replaceWith(node('div', 'Photo coming soon', 'photo-placeholder')));
  return img;
}
function showWelcome() { if (!$('welcome').open) $('welcome').showModal(); }
async function loadIdentity() {
  const data = await api('/api/session');
  csrf = data.csrf;
  state.customer = data.customer;
  $('history-button').hidden = !data.customer;
  if (!data.customer) { $('history-orders').replaceChildren(); $('history-dialog').close(); }
  state.guest = data.guest;
  $('identity').textContent = data.customer ? `${data.customer.full_name} · ${data.customer.points} points` : data.guest ? 'Ordering as a guest' : 'Welcome to Panda';
  $('account-button').textContent = data.customer ? 'Sign out' : 'Log in';
  if (!data.customer && !data.guest) showWelcome();
}
const categoryOrder = ['Meals', 'Entrees', 'Sides', 'Appetizers', 'Drinks', 'Extras'];
async function loadMenu() {
  $('menu-status').textContent = 'Loading the menu…';
  $('retry-menu').hidden = true;
  try {
    state.items = (await api('/api/menu')).items;
    const categories = categoryOrder.filter(c => state.items.some(i => i.category === c));
    for (const item of state.items) if (!categories.includes(item.category)) categories.push(item.category);
    if (!categories.includes(state.category)) state.category = categories[0];
    $('categories').replaceChildren();
    for (const category of categories) {
      const button = node('button', category);
      button.type = 'button';
      button.addEventListener('click', () => { state.category = category; renderMenu(); });
      $('categories').append(button);
    }
    renderMenu(); renderCart();
  } catch (error) {
    $('menu-status').textContent = error.message;
    $('retry-menu').hidden = false;
  }
}
function renderMenu() {
  for (const button of $('categories').children) button.setAttribute('aria-pressed', String(button.textContent === state.category));
  const term = $('search').value.trim().toLowerCase();
  const items = state.items.filter(item => item.category === state.category);
  const groups = new Map();
  for (const item of items) {
    const name = / CUB MEAL \(/i.test(item.name) ? 'Cub Meal' :
      item.category === 'Drinks' ? item.name.replace(/ - (KIDS|SMALL|MEDIUM|MEDDIUM|LARGE)$/, '') : item.name;
    if (!groups.has(name)) groups.set(name, []);
    groups.get(name).push(item);
  }
  $('menu-grid').replaceChildren();
  for (const [name, variants] of groups) {
    if (!name.toLowerCase().includes(term) && !variants.some(v => v.name.toLowerCase().includes(term))) continue;
    const cubMeal = name === 'Cub Meal';
    let item = variants.find(v => v.available) || variants[0];
    const card = node('article', undefined, 'card');
    card.append(photo({...item, name, image: cubMeal ? variants.find(v => v.image)?.image : item.image}));
    const body = node('div', undefined, 'card-body');
    body.append(node('h3', title(name)));
    if (item.entree_count) body.append(node('p', `1 side + ${item.entree_count} ${item.entree_count === 1 ? 'entree' : 'entrees'}`, 'description'));
    const bottom = node('div', undefined, 'card-bottom');
    const price = node('strong', currency(item.price));
    const add = node('button', item.available ? (item.entree_count ? 'Build meal' : '+ Add') : 'Unavailable', 'add');
    add.disabled = !item.available || !!state.pending;
    add.setAttribute('aria-label', `${item.entree_count ? 'Build' : 'Add'} ${title(name)}`);
    if (variants.length > 1) {
      const choiceLabel = cubMeal ? 'Meal' : 'Size';
      const label = node('label', choiceLabel + ' ', 'description');
      const select = node('select');
      select.setAttribute('aria-label', `${choiceLabel} for ${title(name)}`);
      for (const variant of variants) {
        const optionName = cubMeal ? variant.name.split(/ CUB MEAL \(/i)[0] : variant.name.split(' - ').pop();
        const option = node('option', title(optionName) + (!variant.available ? ' (unavailable)' : ''));
        option.value = variant.id;
        option.selected = variant.id === item.id;
        select.append(option);
      }
      select.addEventListener('change', () => {
        item = variants.find(v => v.id === Number(select.value));
        price.textContent = currency(item.price);
        add.disabled = !item.available || !!state.pending;
        add.textContent = item.available ? (item.entree_count ? 'Build meal' : '+ Add') : 'Unavailable';
      });
      label.append(select); body.append(label);
    }
    add.addEventListener('click', () => item.entree_count ? buildMeal(item) : addLine(item, []));
    bottom.append(price, add); body.append(bottom); card.append(body); $('menu-grid').append(card);
  }
  $('menu-status').textContent = $('menu-grid').children.length ? '' : 'No items found. Try another category or search.';
}
function buildMeal(item) {
  $('meal-title').textContent = 'Build your ' + title(item.name);
  $('meal-choices').replaceChildren();
  for (let slot = 0; slot <= item.entree_count; slot++) {
    const field = node('fieldset', undefined, 'choice-field');
    field.append(node('legend', slot === 0 ? 'Choose your side' : `Choose entree ${slot}`));
    const options = node('div', undefined, 'choice-options');
    const choices = state.items.filter(i => i.category === (slot === 0 ? 'Sides' : 'Entrees') && i.available && !i.entree_count && !i.name.includes('SOUP'));
    if (!choices.length) { $('cart-status').textContent = 'This meal is currently unavailable: no meal choices are in stock.'; return; }
    for (const choice of choices) {
      const label = node('label', undefined, 'choice-option');
      label.append(photo(choice));
      const input = node('input');
      input.type = 'radio'; input.name = 'choice-' + slot; input.value = choice.id; input.required = true;
      label.append(input, document.createTextNode(' ' + title(choice.name))); options.append(label);
    }
    field.append(options); $('meal-choices').append(field);
  }
  $('meal-form').onsubmit = event => {
    event.preventDefault();
    const selections = [...new FormData(event.currentTarget).values()].map(Number);
    addLine(item, selections); $('meal-dialog').close();
  };
  $('meal-dialog').showModal();
}
function addLine(item, selections) {
  if (state.pending) return;
  const existing = state.cart.find(line => line.id === item.id && JSON.stringify(line.selections) === JSON.stringify(selections));
  if (existing) { if (existing.quantity < 20) existing.quantity++; }
  else state.cart.push({id: item.id, quantity: 1, selections});
  persist(); renderCart();
}
async function renderCart() {
  const revision = ++state.revision;
  state.quote = null;
  $('cart-lines').replaceChildren();
  $('cart-totals').hidden = true;
  $('review-order').disabled = true;
  $('cart-count').textContent = `${state.cart.reduce((sum, l) => sum + l.quantity, 0)} items`;
  if (!state.cart.length) {
    $('cart-lines').append(node('p', 'Your next favorite is waiting. Add something delicious to get started.', 'empty-cart'));
    $('cart-status').textContent = ''; return;
  }
  state.cart.forEach((line, index) => {
    const item = state.items.find(i => i.id === line.id);
    const row = node('div', undefined, 'cart-line');
    row.append(node('strong', item ? title(item.name) : 'Menu item #' + line.id));
    const choices = (line.selections || []).map(id => title(state.items.find(i => i.id === id)?.name || 'Menu item #' + id));
    if (choices.length) row.append(node('p', choices.join(' / ')));
    const controls = node('div', undefined, 'line-controls');
    for (const [text, delta] of [['−', -1], ['+', 1]]) {
      const button = node('button', text);
      button.setAttribute('aria-label', `${delta < 0 ? 'Decrease' : 'Increase'} quantity of ${item?.name || 'item'}`);
      button.disabled = !!state.pending || (delta > 0 && line.quantity >= 20);
      button.addEventListener('click', () => {
        line.quantity += delta;
        if (!line.quantity) state.cart.splice(index, 1);
        persist(); renderCart();
      });
      controls.append(button);
      if (delta < 0) controls.append(node('span', String(line.quantity)));
    }
    const remove = node('button', 'Remove', 'remove'); remove.disabled = !!state.pending;
    remove.setAttribute('aria-label', `Remove ${item?.name || 'item'} from order`);
    remove.addEventListener('click', () => { state.cart.splice(index, 1); persist(); renderCart(); });
    controls.append(remove); row.append(controls); $('cart-lines').append(row);
  });
  if (state.pending) {
    $('cart-status').textContent = 'An order is awaiting confirmation. Retry to check its status without placing it twice.';
    $('review-order').textContent = 'Retry order confirmation';
    $('review-order').disabled = false;
    return;
  }
  $('review-order').textContent = 'Review order';
  $('cart-status').textContent = 'Checking prices and availability…';
  try {
    const quote = await api('/api/quote', {cart: state.cart});
    if (revision !== state.revision) return;
    state.quote = quote;
    for (const key of ['subtotal', 'tax', 'total']) $(key).textContent = currency(quote[key]);
    $('cart-totals').hidden = false; $('cart-status').textContent = ''; $('review-order').disabled = false;
  } catch (error) { if (revision === state.revision) $('cart-status').textContent = error.message; }
}
$('review-order').addEventListener('click', () => {
  if (!state.customer && !state.guest) { showWelcome(); return; }
  const quote = state.pending?.quote || state.quote;
  if (!quote) return;
  $('checkout-summary').replaceChildren();
  for (const line of quote.lines) {
    $('checkout-summary').append(node('p', `${line.quantity} × ${title(line.name)} — ${currency(line.line_total)}${line.choice_names.length ? '\n' + line.choice_names.map(title).join(' / ') : ''}`));
  }
  $('checkout-summary').append(node('strong', 'Total including tax: ' + currency(quote.total)));
  $('checkout-status').textContent = '';
  $('place-order').textContent = state.pending ? 'Retry confirmation' : 'Place order';
  $('checkout-dialog').showModal();
});
$('place-order').addEventListener('click', async () => {
  if (state.busy) return;
  if (!state.pending && !state.quote) return;
  state.busy = true; $('place-order').disabled = true;
  if (!state.pending) state.pending = {cart: structuredClone(state.cart), expected_total: state.quote.total, checkout_key: crypto.randomUUID(), quote: state.quote};
  persist(); renderMenu(); renderCart();
  $('checkout-status').textContent = 'Sending your order…';
  try {
    const receipt = await api('/api/orders', state.pending);
    state.cart = []; state.pending = null; persist();
    $('checkout-dialog').close();
    $('receipt-message').textContent = `Order #${receipt.order_id} has been placed. Your total is ${currency(receipt.total)}. Please pay at the counter.`;
    $('receipt-dialog').showModal();
    renderMenu(); renderCart();
  } catch (error) {
    // A lost response may hide a committed order. Keep the same request key on retry.
    if ([400, 401, 403, 409].includes(error.status)) {
      state.pending = null; persist();
      $('checkout-dialog').close();
      renderMenu(); await renderCart();
      $('cart-status').textContent = error.message;
      if (error.status === 401) showWelcome();
    } else {
      $('checkout-status').textContent = error.message;
      $('place-order').textContent = 'Retry confirmation';
    }
  } finally { state.busy = false; $('place-order').disabled = false; }
});
$('continue-guest').addEventListener('click', async () => {
  $('continue-guest').disabled = true;
  try { await api('/auth/guest', {}); await loadIdentity(); $('welcome').close(); }
  catch (error) { $('login-status').textContent = error.message; }
  finally { $('continue-guest').disabled = false; }
});
$('account-button').addEventListener('click', async () => {
  if (state.customer) {
    try { await api('/auth/logout', {}); await loadIdentity(); }
    catch (error) { $('cart-status').textContent = error.message; }
  } else showWelcome();
});
for (const button of document.querySelectorAll('[data-close]')) button.addEventListener('click', () => {
  if (state.busy && button.dataset.close === 'checkout-dialog') return;
  $(button.dataset.close).close();
});
$('checkout-dialog').addEventListener('cancel', event => { if (state.busy) event.preventDefault(); });
$('search').addEventListener('input', renderMenu);
$('retry-menu').addEventListener('click', loadMenu);
loadIdentity().catch(error => { $('cart-status').textContent = error.message; });
loadMenu();
if (new URLSearchParams(location.search).has('auth_error')) {
  $('login-status').textContent = 'Google sign-in could not be completed. Try again or continue as a guest.';
  showWelcome();
  history.replaceState(null, '', '/');
}

let historyRevision = 0;
async function loadHistory() {
  const revision = ++historyRevision;
  $('history-orders').replaceChildren();
  $('history-status').textContent = 'Loading your orders…';
  $('retry-history').hidden = true;
  try {
    const data = await api('/api/orders');
    if (revision !== historyRevision || !state.customer) return;
    $('history-status').textContent = data.orders.length ? '' : 'No orders in the past month. Orders placed while signed in will appear here.';
    for (const order of data.orders) {
      const card = node('article', undefined, 'past-order');
      card.append(node('h3', `Order #${order.order_id}`));
      card.append(node('p', new Date(order.placed_at).toLocaleString()));
      for (const line of order.receipt.lines) {
        card.append(node('p', `${line.quantity} × ${title(line.name)}${line.choice_names.length ? ' — ' + line.choice_names.map(title).join(' / ') : ''}`));
      }
      card.append(node('p', 'Original total: ' + currency(order.receipt.total)));
      const button = node('button', 'Reorder', 'button');
      button.disabled = !!state.pending || state.busy;
      button.addEventListener('click', async () => {
        if (state.pending || state.busy) return;
        if (state.cart.length && !window.confirm('Replace your current cart with this previous order?')) return;
        button.disabled = true;
        $('history-status').textContent = 'Checking current prices and availability…';
        try {
          const result = await api(`/api/orders/${order.order_id}/reorder`, {});
          if (!state.customer || state.pending || state.busy) return;
          state.cart = result.cart;
          persist();
          await renderCart();
          $('history-dialog').close();
          $('cart-status').textContent = 'Previous order restored. Review current prices before placing your order.';
          $('cart-title').scrollIntoView({block: 'center'});
        } catch (error) { $('history-status').textContent = error.message; }
        finally { button.disabled = !!state.pending || state.busy; }
      });
      card.append(button); $('history-orders').append(card);
    }
    if (state.pending) $('history-status').textContent = 'Confirm your pending checkout before reordering.';
  } catch (error) {
    if (revision !== historyRevision) return;
    $('history-status').textContent = error.message;
    $('retry-history').hidden = false;
  }
}
$('history-button').addEventListener('click', () => { $('history-dialog').showModal(); loadHistory(); });
$('retry-history').addEventListener('click', loadHistory);
