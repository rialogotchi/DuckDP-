<%@ tag body-content="empty" dynamic-attributes="dynattrs" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>

<div id="dpi-app" class="dpi" data-readonly="${dynattrs.readOnly}">

	<%-- status + start/stop --%>
	<div class="container outer-container">
		<div class="match-alignment white">
			<div class="col span-12">
				<div class="panel white dpi-card">
					<div class="dpi-head">
						<span class="h2">proxy</span>
						<span id="dpi-badge" class="dpi-badge dpi-badge--unknown">loading&hellip;</span>
					</div>
					<p id="dpi-msg" class="dpi-msg"></p>
					<p id="dpi-warn" class="dpi-warn" style="display:none;">
						the proxy listens on a non-local address and has no password: everybody who can reach that address can use it.
					</p>
					<dl class="dpi-facts">
						<dt>SOCKS5 address</dt><dd><code id="dpi-listen">-</code> <button type="button" id="dpi-copy" class="dpi-link" title="copy">copy</button></dd>
						<dt>process</dt><dd id="dpi-proc">-</dd>
						<dt>restarts</dt><dd id="dpi-restarts">0</dd>
						<dt>engine</dt><dd id="dpi-version">-</dd>
					</dl>
					<div class="dpi-buttons">
						<button type="button" id="dpi-start" class="dpi-btn dpi-btn--go">start</button>
						<button type="button" id="dpi-restart" class="dpi-btn">restart</button>
						<button type="button" id="dpi-stop" class="dpi-btn dpi-btn--stop">stop</button>
					</div>
				</div>
			</div>
		</div>
	</div>

	<%-- settings --%>
	<div class="container outer-container">
		<div class="match-alignment white">
			<div class="col span-12">
				<div class="panel white dpi-card">
					<div class="dpi-head"><span class="h2">settings</span></div>
					<div class="dpi-form">
						<label class="dpi-field"><span>listen address</span>
							<input type="text" id="dpi-ip" autocomplete="off" spellcheck="false" maxlength="45"/></label>
						<label class="dpi-field dpi-field--short"><span>port</span>
							<input type="text" id="dpi-port" autocomplete="off" inputmode="numeric" maxlength="5"/></label>
						<label class="dpi-field"><span>fake SNI <em>(fills {sni} in a strategy)</em></span>
							<input type="text" id="dpi-sni" autocomplete="off" spellcheck="false" maxlength="253"/></label>
						<label class="dpi-field dpi-field--wide"><span>strategy preset <em>(from ByeByeDPI's list - the test below finds the one that works for you)</em></span>
							<select id="dpi-preset"></select></label>
						<label class="dpi-field dpi-field--wide"><span>ByeDPI command line <em>(without --ip / --port)</em></span>
							<textarea id="dpi-args" rows="3" spellcheck="false" autocomplete="off"></textarea></label>
						<label class="dpi-check"><input type="checkbox" id="dpi-autostart"/> start the proxy together with this web app</label>
					</div>
					<div class="dpi-buttons">
						<button type="button" id="dpi-save" class="dpi-btn dpi-btn--go">save &amp; apply</button>
						<button type="button" id="dpi-defaults" class="dpi-btn">default arguments</button>
					</div>
					<p id="dpi-feedback" class="dpi-feedback" role="status"></p>
					<p id="dpi-locked" class="dpi-msg" style="display:none;">settings are locked on this server (account-read-only=true).</p>
				</div>
			</div>
		</div>
	</div>

	<%-- strategy test --%>
	<div class="container outer-container">
		<div class="match-alignment white">
			<div class="col span-12">
				<div class="panel white dpi-card">
					<div class="dpi-head"><span class="h2">test</span></div>
					<p class="dpi-msg">fetches the chosen sites through a throw-away copy of the proxy and counts how many answer completely.
						"test these settings" checks the command line above; "find a strategy" tries all presets one after the other (this takes a while).</p>
					<div id="dpi-lists" class="dpi-lists"></div>
					<div class="dpi-form">
						<label class="dpi-field dpi-field--wide"><span>more sites <em>(domain names, separated by spaces or commas)</em></span>
							<input type="text" id="dpi-custom" autocomplete="off" spellcheck="false"/></label>
						<label class="dpi-field dpi-field--short"><span>requests per site</span>
							<input type="text" id="dpi-requests" value="1" inputmode="numeric" maxlength="1"/></label>
						<label class="dpi-field dpi-field--short"><span>timeout (s)</span>
							<input type="text" id="dpi-timeout" value="5" inputmode="numeric" maxlength="2"/></label>
					</div>
					<div class="dpi-buttons">
						<button type="button" id="dpi-test-current" class="dpi-btn dpi-btn--go">test these settings</button>
						<button type="button" id="dpi-test-scan" class="dpi-btn">find a strategy</button>
						<button type="button" id="dpi-test-cancel" class="dpi-btn dpi-btn--stop" style="display:none;">cancel</button>
					</div>
					<p id="dpi-test-msg" class="dpi-feedback" role="status"></p>
					<div class="dpi-progress" id="dpi-progress" style="display:none;"><div id="dpi-progress-bar"></div></div>
					<div class="dpi-tablewrap">
						<table class="dpi-table" id="dpi-results" style="display:none;">
							<thead><tr><th>result</th><th>time</th><th>command line</th><th></th></tr></thead>
							<tbody></tbody>
						</table>
					</div>
				</div>
			</div>
		</div>
	</div>

	<%-- log --%>
	<div class="container outer-container">
		<div class="match-alignment white">
			<div class="col span-12">
				<div class="panel white dpi-card">
					<div class="dpi-head"><span class="h2">log</span></div>
					<pre id="dpi-log" class="dpi-log"></pre>
				</div>
			</div>
		</div>
	</div>
</div>
