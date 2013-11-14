package com.dianping.cat.consumer.browser;

import java.util.List;

import org.codehaus.plexus.logging.LogEnabled;
import org.codehaus.plexus.logging.Logger;
import org.unidal.lookup.annotation.Inject;

import com.dianping.cat.analysis.AbstractMessageAnalyzer;
import com.dianping.cat.consumer.browser.model.entity.Browser;
import com.dianping.cat.consumer.browser.model.entity.BrowserReport;
import com.dianping.cat.consumer.browser.model.entity.BrowserVersion;
import com.dianping.cat.consumer.browser.model.entity.DomainDetail;
import com.dianping.cat.consumer.browser.model.entity.Os;
import com.dianping.cat.message.Message;
import com.dianping.cat.message.Transaction;
import com.dianping.cat.message.spi.MessageTree;
import com.dianping.cat.service.ReportManager;
import com.dianping.cat.service.DefaultReportManager.StoragePolicy;

public class BrowserAnalyzer extends AbstractMessageAnalyzer<BrowserReport> implements LogEnabled {
	public static final String ID = "browser";

	@Inject(ID)
	private ReportManager<BrowserReport> m_reportManager;

	@Override
	public void doCheckpoint(boolean atEnd) {
		if (atEnd && !isLocalMode()) {
			m_reportManager.storeHourlyReports(getStartTime(), StoragePolicy.FILE_AND_DB);
		} else {
			m_reportManager.storeHourlyReports(getStartTime(), StoragePolicy.FILE);
		}
	}

	@Override
	public void enableLogging(Logger logger) {
		m_logger = logger;
	}

	@Override
	public BrowserReport getReport(String domain) {
		BrowserReport report = m_reportManager.getHourlyReport(getStartTime(), domain, false);

		report.getDomainNames().addAll(m_reportManager.getDomains(getStartTime()));
		return report;
	}

	protected String parseValue(final String key, final String data) {
		int len = data == null ? 0 : data.length();
		int keyLen = key.length();
		StringBuilder name = new StringBuilder();
		StringBuilder value = new StringBuilder();
		boolean inName = true;

		for (int i = 0; i < len; i++) {
			char ch = data.charAt(i);

			switch (ch) {
			case '&':
				if (name.length() == keyLen && name.toString().equals(key)) {
					return value.toString();
				}
				inName = true;
				name.setLength(0);
				value.setLength(0);
				break;
			case '=':
				if (inName) {
					inName = false;
				} else {
					value.append(ch);
				}
				break;
			default:
				if (inName) {
					name.append(ch);
				} else {
					value.append(ch);
				}
				break;
			}
		}

		if (name.length() == keyLen && name.toString().equals(key)) {
			return value.toString();
		}

		return null;
	}

	@Override
	protected void process(MessageTree tree) {
		Message message = tree.getMessage();
		if (message instanceof Transaction) {
			String type = message.getType();

			if ("URL".equals(type)) {
				List<Message> children = ((Transaction) message).getChildren();

				for (Message child : children) {
					String childType = child.getType();
					String childName = child.getName();
					BrowserReport report = m_reportManager.getHourlyReport(getStartTime(), "Cat", true);

					if ("URL".equals(childType) && ("URL.Server".equals(childName) || "ClientInfo".equals(childName))) {
						String data = (String) child.getData();
						String domain = tree.getDomain();

						updateBrowserReport(report, domain, data);
						return;
					}
				}
			}
		}

	}

	private void updateBrowserReport(BrowserReport report, String domain, String data) {
		String agent = parseValue("Agent", data);

		if (agent == null || agent.isEmpty()) {
			m_logger.error("Can not get agent from url when browser analyze: " + data);
		}

		UserAgentParser uap = null;
		try {
			uap = new UserAgentParser(agent);
		} catch (UserAgentParseException e) {
			m_logger.error(e.getMessage(), e);
		}
		String browserName = uap.getBrowserName();
		String osName = uap.getBrowserOperatingSystem();
		String versionName = uap.getBrowserVersion();
		DomainDetail detail = report.findOrCreateDomainDetail(domain);

		Browser browser = detail.findOrCreateBrowser(browserName);
		BrowserVersion version = browser.findOrCreateBrowserVersion(versionName);
		Os os = detail.findOrCreateOs(osName);

		os.setCount(os.getCount() + 1);
		version.setCount(version.getCount() + 1);
	}
}
