package egovframework.com.cmm.config;

import egovframework.com.cmm.filter.HTMLTagFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.WebApplicationInitializer;
import org.springframework.web.context.ContextLoaderListener;
import org.springframework.web.context.request.RequestContextListener;
import org.springframework.web.context.support.XmlWebApplicationContext;
import org.springframework.web.multipart.support.MultipartFilter;
import org.springframework.web.servlet.DispatcherServlet;

import javax.servlet.FilterRegistration;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRegistration;

public class EgovWebApplicationInitializer implements WebApplicationInitializer {

	private static final Logger LOGGER = LoggerFactory.getLogger(EgovWebApplicationInitializer.class);

	@Override
	public void onStartup(ServletContext servletContext) throws ServletException {
		LOGGER.debug("EgovWebApplicationInitializer START");

		servletContext.addListener(new egovframework.com.cmm.context.EgovWebServletContextListener());

		FilterRegistration.Dynamic characterEncoding = servletContext.addFilter("encodingFilter", new org.springframework.web.filter.CharacterEncodingFilter());
		characterEncoding.setInitParameter("encoding", "UTF-8");
		characterEncoding.setInitParameter("forceEncoding", "true");
		characterEncoding.addMappingForUrlPatterns(null, false, "*.do");

		XmlWebApplicationContext rootContext = new XmlWebApplicationContext();
		rootContext.setConfigLocations(
				new String[] {"classpath*:spring/com/root-context.xml"
				, "classpath*:spring/com/context-*.xml"}
				);

		rootContext.refresh();
		rootContext.start();

		servletContext.addListener(new ContextLoaderListener(rootContext));

		XmlWebApplicationContext xmlWebApplicationContext = new XmlWebApplicationContext();
		xmlWebApplicationContext.setConfigLocations("classpath:spring/com/webAppServlet.xml");
		ServletRegistration.Dynamic dispatcher = servletContext.addServlet("dispatcher", new DispatcherServlet(xmlWebApplicationContext));
		dispatcher.addMapping("/");
		dispatcher.setLoadOnStartup(1);

		MultipartFilter springMultipartFilter = new MultipartFilter();
		springMultipartFilter.setMultipartResolverBeanName("multipartResolver");
		FilterRegistration.Dynamic multipartFilter = servletContext.addFilter("springMultipartFilter", springMultipartFilter);
		multipartFilter.addMappingForUrlPatterns(null, false, "*.do");

		FilterRegistration.Dynamic htmlTagFilter = servletContext.addFilter("htmlTagFilter", new HTMLTagFilter());
		htmlTagFilter.addMappingForUrlPatterns(null, false, "*.do");

		servletContext.addListener(new RequestContextListener());

		LOGGER.debug("EgovWebApplicationInitializer END");
	}

}
