package org.duckdns.servlets;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.duckdns.dao.PocketBaseDAO;
import org.duckdns.dao.model.DpiSettings;
import org.duckdns.dpi.DpiService;
import org.duckdns.dpi.DpiTester;

/**
 * Starts the DPI proxy together with the web app (when "autostart" is on) and
 * stops it - and any running test - when the app goes down.
 */
public class DpiLifecycleListener implements ServletContextListener {

	private static final Log LOG = LogFactory.getLog(DpiLifecycleListener.class);

	@Override
	public void contextInitialized(ServletContextEvent event) {
		// in its own thread: the data store may still be coming up and the deployment must not wait for it
		Thread t = new Thread("dpi-autostart") {
			@Override
			public void run() {
				for (int attempt = 0; attempt < 30; attempt++) {
					try {
						DpiSettings s = PocketBaseDAO.getInstance().dpiGetSettings();
						if (s.isAutostart()) {
							DpiService.Result r = DpiService.getInstance().start(s);
							LOG.info("DPI proxy autostart: " + (r.ok ? "ok" : "failed - " + r.message));
						} else {
							LOG.info("DPI proxy autostart is off");
						}
						return;
					} catch (RuntimeException e) {
						LOG.warn("DPI autostart: data store not ready yet (" + e.getMessage() + ")");
						try {
							Thread.sleep(2000);
						} catch (InterruptedException ie) {
							return;
						}
					}
				}
				LOG.error("DPI autostart gave up: the data store never answered");
			}
		};
		t.setDaemon(true);
		t.start();
	}

	@Override
	public void contextDestroyed(ServletContextEvent event) {
		DpiTester.getInstance().cancel();
		DpiService.getInstance().stop();
	}
}
