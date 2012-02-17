package com.dianping.cat.report.page.ip;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.ServletException;

import com.dianping.cat.consumer.ip.model.entity.Ip;
import com.dianping.cat.consumer.ip.model.entity.IpReport;
import com.dianping.cat.consumer.ip.model.entity.Period;
import com.dianping.cat.consumer.ip.model.transform.BaseVisitor;
import com.dianping.cat.report.ReportPage;
import com.dianping.cat.report.ServerConfig;
import com.dianping.cat.report.tool.Constants;
import com.dianping.cat.report.tool.DateUtils;
import com.dianping.cat.report.tool.ReportUtils;
import com.dianping.cat.report.tool.StringUtils;
import com.site.lookup.annotation.Inject;
import com.site.web.mvc.PageHandler;
import com.site.web.mvc.annotation.InboundActionMeta;
import com.site.web.mvc.annotation.OutboundActionMeta;
import com.site.web.mvc.annotation.PayloadMeta;

public class Handler implements PageHandler<Context> {
	@Inject
	private JspViewer m_jspViewer;
	
	@Inject
	private ServerConfig serverConfig;
	
	@Inject
	private IpManager m_manager;

	@Override
	@PayloadMeta(Payload.class)
	@InboundActionMeta(name = "ip")
	public void handleInbound(Context ctx) throws ServletException, IOException {
		// display only, no action here
	}

	@Override
	@OutboundActionMeta(name = "ip")
	public void handleOutbound(Context ctx) throws ServletException, IOException {
		Model model = new Model(ctx);
		Payload payload = ctx.getPayload();
		model.setAction(Action.VIEW);
		model.setPage(ReportPage.IP);
		
		long currentTimeMillis = System.currentTimeMillis();
		long currentTime = currentTimeMillis - currentTimeMillis % DateUtils.HOUR;
		String domain = payload.getDomain();
		String urlCurrent = payload.getCurrent();
		int method = payload.getMethod();
		long reportStart = m_manager.computeReportStartHour(currentTime,urlCurrent, method);
		IpReport report = null ;
		String index = m_manager.getReportStartType(currentTime,reportStart);
		model.setCurrent(DateUtils.SDF_URL.format(new Date(reportStart)));

		if (index.equals(Constants.MEMORY_CURRENT) || index.equals(Constants.MEMORY_LAST)) {
			List<IpReport> reports = new ArrayList<IpReport>();
			List<String> servers = serverConfig.getConsumerServers();
			Set<String> domains = new HashSet<String>();
			for (String server : servers) {
				String connectionUrl = m_manager.getConnectionUrl(server, domain,index);
				String pageResult = m_manager.getRemotePageContent(connectionUrl);
				if(pageResult!=null){
					List<String> domainTemps = StringUtils.getListFromPage(pageResult, "<domains>", "</domains>");
					if (domainTemps != null) {
						for (String temp : domainTemps) {
							domains.add(temp);
						}
					}
					String xml = StringUtils.getStringFromPage(pageResult, "<data>", "</data>");
					reports.add(ReportUtils.parseIpReportXML(xml));
				}else{
					// TODO the remote server have some problem
				}
			}
			report = ReportUtils.mergeIpReports(reports);
			List<String> domainList = new ArrayList<String>(domains);
			Collections.sort(domainList);
			model.setDomains(domainList);
			model.setCurrentDomain(report.getDomain());
			model.setGenerateTime(DateUtils.SDF_SEG.format(new Date()));
		} else {
			// TODO
			model.setGenerateTime(DateUtils.SDF_SEG.format(new Date(reportStart+DateUtils.HOUR)));
			String reportFileName = m_manager.getReportStoreFile(reportStart, payload.getDomain());
			System.out.println(reportFileName);
		}
		model.setReportTitle(m_manager.getReportDisplayTitle( model.getCurrentDomain(), reportStart));

		Calendar cal = Calendar.getInstance();
		int minute = cal.get(Calendar.MINUTE);
		Map<String, DisplayModel> models = new HashMap<String, DisplayModel>();
		DisplayModelBuilder builder = new DisplayModelBuilder(models, minute);

		report.accept(builder); // prepare display model

		List<DisplayModel> displayModels = new ArrayList<DisplayModel>(models.values());

		Collections.sort(displayModels, new Comparator<DisplayModel>() {
			@Override
			public int compare(DisplayModel m1, DisplayModel m2) {
				return m2.getLastFifteen() - m1.getLastFifteen(); // desc
			}
		});

		model.setReport(report);
		model.setDisplayModels(displayModels);
		m_jspViewer.view(ctx, model);
	}

	static class DisplayModelBuilder extends BaseVisitor {
		private int m_minute;

		private Map<String, DisplayModel> m_models;

		private Period m_period;

		public DisplayModelBuilder(Map<String, DisplayModel> models, int minute) {
			m_models = models;
			m_minute = minute;
		}

		@Override
		public void visitIp(Ip ip) {
			String address = ip.getAddress();
			DisplayModel model = m_models.get(address);

			if (model == null) {
				model = new DisplayModel(address);
				m_models.put(address, model);
			}

			model.process(m_minute, m_period.getMinute(), ip.getCount());
		}

		@Override
		public void visitPeriod(Period period) {
			m_period = period;
			super.visitPeriod(period);
		}
	}
}
