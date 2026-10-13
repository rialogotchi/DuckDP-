// Control panel of the DPI (ByeDPI) service. Talks to the /dpi JSON endpoint.
// Plain JavaScript, no libraries, everything the server sends is inserted with textContent.
(function () {
	'use strict';

	var app = document.getElementById('dpi-app');
	if (!app) {
		return;
	}
	var csrfMeta = document.querySelector('meta[name="csrf-token"]');
	var CSRF = csrfMeta ? csrfMeta.getAttribute('content') : '';
	var READ_ONLY = app.getAttribute('data-readonly') === 'true';

	function $(id) {
		return document.getElementById(id);
	}

	var meta = null;          // presets + site lists
	var state = null;         // last state from the server
	var formLoaded = false;   // the form is filled from the server once, then the user owns it
	var busy = false;
	var pollTimer = null;
	var lastTestKey = '';

	// ------------------------------------------------------------ server calls

	function call(action, params) {
		var post = action !== 'state' && action !== 'meta';
		var opts = { credentials: 'same-origin', headers: {}, cache: 'no-store' };
		var url = 'dpi';
		if (post) {
			var body = new URLSearchParams();
			body.set('action', action);
			for (var k in params) {
				if (Object.prototype.hasOwnProperty.call(params, k)) {
					body.set(k, params[k]);
				}
			}
			opts.method = 'POST';
			opts.body = body;
			opts.headers['X-CSRF-Token'] = CSRF;
		} else {
			url += '?action=' + encodeURIComponent(action);
		}
		return fetch(url, opts).then(function (r) {
			return r.json().then(function (j) {
				j.httpStatus = r.status;
				return j;
			}, function () {
				return { ok: false, message: 'unexpected answer (HTTP ' + r.status + ')', httpStatus: r.status };
			});
		});
	}

	// ------------------------------------------------------------ helpers

	function setText(id, text) {
		var el = $(id);
		if (el && el.textContent !== text) {
			el.textContent = text;
		}
	}

	function feedback(id, ok, text) {
		var el = $(id);
		el.textContent = text || '';
		el.className = 'dpi-feedback' + (text ? (ok ? ' dpi-feedback--ok' : ' dpi-feedback--bad') : '');
	}

	function fmtDuration(sec) {
		sec = Math.max(0, Math.floor(sec));
		var d = Math.floor(sec / 86400), h = Math.floor(sec % 86400 / 3600), m = Math.floor(sec % 3600 / 60), s = sec % 60;
		if (d > 0) { return d + 'd ' + h + 'h'; }
		if (h > 0) { return h + 'h ' + m + 'm'; }
		if (m > 0) { return m + 'm ' + s + 's'; }
		return s + 's';
	}

	function selectedLists() {
		var out = [];
		var boxes = $('dpi-lists').querySelectorAll('input[type=checkbox]');
		for (var i = 0; i < boxes.length; i++) {
			if (boxes[i].checked) {
				out.push(boxes[i].value);
			}
		}
		return out;
	}

	// ------------------------------------------------------------ rendering

	var BADGES = {
		running: ['running', 'dpi-badge--ok'],
		starting: ['starting', 'dpi-badge--wait'],
		stopped: ['stopped', 'dpi-badge--off'],
		failed: ['failed', 'dpi-badge--bad'],
		unavailable: ['engine missing', 'dpi-badge--bad']
	};

	function renderService(st) {
		var svc = st.service;
		var b = BADGES[svc.state] || [svc.state, 'dpi-badge--off'];
		var badge = $('dpi-badge');
		badge.textContent = b[0];
		badge.className = 'dpi-badge ' + b[1];
		setText('dpi-msg', svc.message || '');
		var running = svc.state === 'running';
		setText('dpi-listen', running ? st.settings.effectiveListen : st.settings.effectiveListen + '  (not running)');
		var proc = '-';
		if (running) {
			proc = 'pid ' + (svc.pid || '?') + ', up ' + fmtDuration(svc.uptimeSec);
		} else if (svc.lastExit !== undefined) {
			proc = 'last exit code ' + svc.lastExit;
		}
		setText('dpi-proc', proc);
		setText('dpi-restarts', String(svc.restarts) + (svc.restarts > 0 ? ' (after crashes)' : ''));
		setText('dpi-version', (svc.version || 'ByeDPI (ciadpi)') + (svc.binaryAvailable ? '' : ' - not found'));
		$('dpi-warn').style.display = st.settings.exposed ? '' : 'none';

		var canAct = !busy && svc.binaryAvailable;
		$('dpi-start').disabled = !canAct || running || svc.state === 'starting';
		$('dpi-restart').disabled = !canAct;
		$('dpi-stop').disabled = busy || !(running || svc.state === 'starting');
	}

	function renderLog(st) {
		var el = $('dpi-log');
		var text = st.log.join('\n');
		if (el.getAttribute('data-len') !== String(st.log.length) + ':' + (st.log.length ? st.log[st.log.length - 1] : '')) {
			var atBottom = el.scrollTop + el.clientHeight >= el.scrollHeight - 8;
			el.textContent = text || '(nothing yet)';
			el.setAttribute('data-len', String(st.log.length) + ':' + (st.log.length ? st.log[st.log.length - 1] : ''));
			if (atBottom) {
				el.scrollTop = el.scrollHeight;
			}
		}
	}

	function presetIdFor(args) {
		if (!meta) { return ''; }
		for (var i = 0; i < meta.presets.length; i++) {
			if (meta.presets[i].args === args) {
				return meta.presets[i].id;
			}
		}
		return '';
	}

	function fillForm(settings) {
		$('dpi-ip').value = settings.ip;
		$('dpi-port').value = String(settings.port);
		$('dpi-sni').value = settings.sni;
		$('dpi-args').value = settings.args;
		$('dpi-autostart').checked = !!settings.autostart;
		$('dpi-preset').value = presetIdFor(settings.args);
		formLoaded = true;
	}

	function renderTest(st) {
		var t = st.test;
		var rows = t.rows || [];
		var table = $('dpi-results');
		var busyTest = t.running;
		$('dpi-test-current').disabled = busyTest;
		$('dpi-test-scan').disabled = busyTest;
		$('dpi-test-cancel').style.display = busyTest ? '' : 'none';

		if (rows.length === 0) {
			table.style.display = 'none';
			$('dpi-progress').style.display = 'none';
			return;
		}
		var done = 0;
		for (var i = 0; i < rows.length; i++) {
			if (rows[i].status === 'done' || rows[i].status === 'error' || rows[i].status === 'skipped') { done++; }
		}
		$('dpi-progress').style.display = busyTest ? '' : 'none';
		$('dpi-progress-bar').style.width = Math.round(100 * done / rows.length) + '%';
		if (busyTest) {
			feedback('dpi-test-msg', true, 'testing ' + (done + 1 <= rows.length ? done + 1 : rows.length) + ' of ' + rows.length + ' - ' + t.sites.length + ' site(s)');
		} else if (t.finished) {
			feedback('dpi-test-msg', !t.message, t.message || (t.cancelled ? 'cancelled' : 'finished') + ' - "use" copies a command line into the settings above');
		}

		// re-render only when something changed
		var key = t.startedAt + '|' + t.running + '|' + rows.map(function (r) { return r.status + r.ok; }).join(',');
		if (key === lastTestKey) {
			return;
		}
		lastTestKey = key;

		var order = rows.map(function (r, i) { return i; });
		if (t.finished && t.mode === 'scan') {
			order.sort(function (a, b) {
				var ra = rows[a], rb = rows[b];
				var pa = ra.status === 'done' && ra.total ? ra.ok / ra.total : -1;
				var pb = rb.status === 'done' && rb.total ? rb.ok / rb.total : -1;
				return pb - pa || ra.ms - rb.ms || a - b;
			});
		}
		var tbody = table.querySelector('tbody');
		while (tbody.firstChild) { tbody.removeChild(tbody.firstChild); }
		order.forEach(function (idx) {
			var r = rows[idx];
			var tr = document.createElement('tr');
			var td = document.createElement('td');
			td.className = 'dpi-result';
			var label = document.createElement('span');
			if (r.status === 'done') {
				var pct = r.total ? Math.round(100 * r.ok / r.total) : 0;
				label.textContent = r.ok + '/' + r.total + '  ' + pct + '%';
				label.className = 'dpi-pct ' + (pct >= 80 ? 'dpi-pct--good' : pct >= 40 ? 'dpi-pct--mid' : 'dpi-pct--low');
			} else if (r.status === 'error') {
				label.textContent = 'error';
				label.className = 'dpi-pct dpi-pct--low';
				label.title = r.error;
			} else {
				label.textContent = r.status === 'running' ? 'testing...' : r.status === 'skipped' ? 'skipped' : 'waiting';
				label.className = 'dpi-pct dpi-pct--wait';
			}
			td.appendChild(label);
			tr.appendChild(td);
			var tm = document.createElement('td');
			tm.textContent = r.status === 'done' || r.status === 'error' ? (r.ms / 1000).toFixed(1) + 's' : '';
			tr.appendChild(tm);
			var cmd = document.createElement('td');
			var code = document.createElement('code');
			code.textContent = r.args;
			cmd.appendChild(code);
			if (r.status === 'error' && r.error) {
				var er = document.createElement('div');
				er.className = 'dpi-rowerr';
				er.textContent = r.error;
				cmd.appendChild(er);
			}
			tr.appendChild(cmd);
			var act = document.createElement('td');
			if (r.status === 'done') {
				var use = document.createElement('button');
				use.type = 'button';
				use.className = 'dpi-link';
				use.textContent = 'use';
				use.addEventListener('click', function () { useArgs(r.args); });
				act.appendChild(use);
			}
			tr.appendChild(act);
			tbody.appendChild(tr);
		});
		table.style.display = '';
	}

	function useArgs(args) {
		$('dpi-args').value = args;
		$('dpi-preset').value = presetIdFor(args);
		feedback('dpi-feedback', true, 'command line copied - press "save & apply" to use it');
		$('dpi-args').scrollIntoView({ behavior: 'smooth', block: 'center' });
	}

	function render(st) {
		state = st;
		renderService(st);
		renderLog(st);
		renderTest(st);
		if (!formLoaded && meta) {
			fillForm(st.settings);
		}
	}

	// ------------------------------------------------------------ actions

	function refresh() {
		return call('state').then(function (r) {
			if (r.httpStatus === 401) {
				setText('dpi-msg', 'your session has expired - reload the page and log in again');
				return;
			}
			if (r.httpStatus === 503) {
				setText('dpi-msg', r.message);
				return;
			}
			if (r.state) {
				render(r.state);
			}
		}).catch(function () {
			setText('dpi-msg', 'cannot reach the server');
		});
	}

	function act(action, params, feedbackId, button) {
		busy = true;
		if (button) { button.disabled = true; }
		function release() {
			busy = false;
			if (button) { button.disabled = false; }
			if (READ_ONLY) { $('dpi-save').disabled = true; }
		}
		return call(action, params || {}).then(function (r) {
			release();
			if (r.state) {
				render(r.state);
			}
			feedback(feedbackId || 'dpi-feedback', !!r.ok, r.message || '');
			if (!r.state && r.httpStatus === 403) {
				feedback(feedbackId || 'dpi-feedback', false, r.message);
			}
			schedule();
			return r;
		}, function () {
			release();
			feedback(feedbackId || 'dpi-feedback', false, 'cannot reach the server');
		});
	}

	function saveParams(restart) {
		return {
			ip: $('dpi-ip').value,
			port: $('dpi-port').value,
			sni: $('dpi-sni').value,
			args: $('dpi-args').value,
			preset: $('dpi-preset').value,
			autostart: $('dpi-autostart').checked ? 'true' : 'false',
			restart: restart ? 'true' : 'false'
		};
	}

	function testParams(mode) {
		return {
			mode: mode,
			lists: selectedLists().join(','),
			custom: $('dpi-custom').value,
			requests: $('dpi-requests').value,
			timeout: $('dpi-timeout').value,
			args: $('dpi-args').value,
			sni: $('dpi-sni').value
		};
	}

	function schedule() {
		if (pollTimer) { clearTimeout(pollTimer); }
		var fast = state && (state.test.running || state.service.state === 'starting');
		pollTimer = setTimeout(function () { refresh().then(schedule); }, fast ? 1000 : 3000);
	}

	// ------------------------------------------------------------ setup

	function buildPresets() {
		var sel = $('dpi-preset');
		var o = document.createElement('option');
		o.value = '';
		o.textContent = 'custom command line';
		sel.appendChild(o);
		meta.presets.forEach(function (p) {
			var opt = document.createElement('option');
			opt.value = p.id;
			var txt = p.args.length > 70 ? p.args.substring(0, 70) + '...' : p.args;
			opt.textContent = '#' + p.id + '  ' + txt;
			sel.appendChild(opt);
		});
		sel.addEventListener('change', function () {
			if (sel.value) {
				for (var i = 0; i < meta.presets.length; i++) {
					if (meta.presets[i].id === sel.value) {
						$('dpi-args').value = meta.presets[i].args;
					}
				}
			}
		});
		$('dpi-args').addEventListener('input', function () {
			sel.value = presetIdFor($('dpi-args').value.replace(/\s+/g, ' ').trim());
		});
	}

	function buildLists() {
		var box = $('dpi-lists');
		meta.siteLists.forEach(function (l) {
			var lab = document.createElement('label');
			lab.className = 'dpi-check';
			var cb = document.createElement('input');
			cb.type = 'checkbox';
			cb.value = l.id;
			cb.checked = l.id === 'general';
			lab.appendChild(cb);
			lab.appendChild(document.createTextNode(' ' + l.label + ' (' + l.count + ')'));
			box.appendChild(lab);
		});
	}

	function bind() {
		$('dpi-start').addEventListener('click', function () { act('start', {}, 'dpi-feedback', this); });
		$('dpi-stop').addEventListener('click', function () { act('stop', {}, 'dpi-feedback', this); });
		$('dpi-restart').addEventListener('click', function () { act('restart', {}, 'dpi-feedback', this); });
		$('dpi-save').addEventListener('click', function () { act('save', saveParams(false), 'dpi-feedback', this); });
		$('dpi-defaults').addEventListener('click', function () {
			$('dpi-args').value = meta.defaultArgs;
			$('dpi-preset').value = '';
		});
		$('dpi-test-current').addEventListener('click', function () { lastTestKey = ''; act('test-start', testParams('current'), 'dpi-test-msg', this); });
		$('dpi-test-scan').addEventListener('click', function () {
			if (window.confirm('This tries every preset one after the other and can take many minutes. Start?')) {
				lastTestKey = '';
				act('test-start', testParams('scan'), 'dpi-test-msg', this);
			}
		});
		$('dpi-test-cancel').addEventListener('click', function () { act('test-cancel', {}, 'dpi-test-msg', this); });
		$('dpi-copy').addEventListener('click', function () {
			var text = 'socks5://' + (state ? state.settings.effectiveListen : '');
			if (navigator.clipboard && navigator.clipboard.writeText) {
				navigator.clipboard.writeText(text);
				feedback('dpi-feedback', true, 'copied ' + text);
			}
		});
		if (READ_ONLY) {
			$('dpi-save').disabled = true;
			$('dpi-locked').style.display = '';
		}
	}

	call('meta').then(function (m) {
		meta = m;
		buildPresets();
		buildLists();
		bind();
		return refresh();
	}).then(schedule);
})();
