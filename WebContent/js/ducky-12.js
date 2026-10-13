// Small page helpers (confirmations, account menu). Plain JavaScript - no jQuery, no CDN:
// the page must work on a network where the outside world is blocked.
(function () {
	'use strict';

	function each(selector, fn) {
		var list = document.querySelectorAll(selector);
		for (var i = 0; i < list.length; i++) {
			fn(list[i]);
		}
	}

	each('.deleteaccount', function (el) {
		el.addEventListener('click', function (event) {
			if (!window.confirm('are you sure that you want to delete your account?') ||
					!window.confirm('are you sure you\'re sure that you want to delete your account?')) {
				event.preventDefault();
			}
		});
	});

	each('.recreatetoken', function (el) {
		el.addEventListener('click', function (event) {
			if (!window.confirm('are you sure that you want to recreate your token?\nscripts using the old one stop working.')) {
				event.preventDefault();
			}
		});
	});

	var toggle = document.getElementById('accountOptionsToggle');
	var holder = document.getElementById('accountOptionsHolder');
	if (toggle && holder) {
		toggle.addEventListener('click', function (event) {
			event.preventDefault();
			holder.style.display = window.getComputedStyle(holder).display === 'none' ? 'block' : 'none';
		});
	}
})();
