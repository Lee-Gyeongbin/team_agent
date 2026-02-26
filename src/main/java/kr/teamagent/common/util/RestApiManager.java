package kr.teamagent.common.util;

import org.apache.http.HttpEntity;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class RestApiManager {
	private final Logger logger = LoggerFactory.getLogger(this.getClass());

	public String postResponseString(String apiUrl, Map<String, String> param, Map<String, String> header) throws Exception {
		try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
			HttpPost request = new HttpPost(apiUrl);
			request.setHeader("Content-Type", "application/json; charset=UTF-8");
			if (header != null) {
				header.forEach(request::setHeader);
			}
			if (param != null) {
				JSONObject json = new JSONObject();
				json.putAll(param);
				request.setEntity(new StringEntity(json.toJSONString(), "UTF-8"));
			}
			try (CloseableHttpResponse response = httpClient.execute(request)) {
				int statusCode = response.getStatusLine().getStatusCode();
				HttpEntity entity = response.getEntity();
				String body = entity != null ? EntityUtils.toString(entity, "UTF-8") : "";
				if (statusCode >= 200 && statusCode < 300) {
					return body;
				} else {
					String errMsg = "Response error: " + statusCode;
					logger.error(errMsg);
					throw new Exception(errMsg);
				}
			}
		} catch (IOException e) {
			logger.error("IOException occurred", e);
			throw new Exception("Failed to execute POST request", e);
		}
	}

	public JSONObject postResponseJson(String apiUrl, Map<String, String> param, Map<String, String> header) throws Exception {
		String responseString = postResponseString(apiUrl, param, header);
		try {
			JSONParser jsonParser = new JSONParser();
			return (JSONObject) jsonParser.parse(responseString);
		} catch (Exception e) {
			logger.error("Error parsing JSON response", e);
			throw new Exception("Failed to parse JSON response", e);
		}
	}

	public String getResponseString(String apiUrl, Map<String, String> header) throws Exception {
		try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
			HttpGet request = new HttpGet(apiUrl);
			if (header != null) {
				header.forEach(request::setHeader);
			}
			try (CloseableHttpResponse response = httpClient.execute(request)) {
				int statusCode = response.getStatusLine().getStatusCode();
				HttpEntity entity = response.getEntity();
				String body = entity != null ? EntityUtils.toString(entity, "UTF-8") : "";
				logger.debug("Response code: {}", statusCode);
				if (statusCode >= 200 && statusCode < 300) {
					return body;
				} else {
					String errMsg = "Response error: " + statusCode;
					logger.error(errMsg);
					throw new Exception(errMsg);
				}
			}
		} catch (IOException e) {
			logger.error("IOException occurred", e);
			throw new Exception("Failed to execute GET request", e);
		}
	}

	public JSONObject getResponseJson(String apiUrl, Map<String, String> header) throws Exception {
		String responseString = getResponseString(apiUrl, header);
		try {
			JSONParser jsonParser = new JSONParser();
			return (JSONObject) jsonParser.parse(responseString);
		} catch (Exception e) {
			logger.error("Error parsing JSON response", e);
			throw new Exception("Failed to parse JSON response", e);
		}
	}

	public String postResponseForm(String apiUrl, Map<String, String> param, Map<String, String> header) throws Exception {
		try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
			HttpPost request = new HttpPost(apiUrl);
			if (header != null) {
				header.forEach(request::setHeader);
			}
			if (param != null) {
				List<NameValuePair> formParams = new ArrayList<>();
				param.forEach((k, v) -> formParams.add(new BasicNameValuePair(k, v)));
				request.setEntity(new UrlEncodedFormEntity(formParams, "UTF-8"));
			}
			try (CloseableHttpResponse response = httpClient.execute(request)) {
				int statusCode = response.getStatusLine().getStatusCode();
				HttpEntity entity = response.getEntity();
				String body = entity != null ? EntityUtils.toString(entity, "UTF-8") : "";
				if (statusCode >= 200 && statusCode < 300) {
					return body;
				} else {
					String errMsg = "Response error: " + statusCode;
					logger.error(errMsg);
					throw new Exception(errMsg);
				}
			}
		} catch (IOException e) {
			logger.error("IOException occurred", e);
			throw new Exception("Failed to execute POST form request", e);
		}
	}

	public JSONObject postResponseFormJson(String apiUrl, Map<String, String> param, Map<String, String> header) throws Exception {
		String responseString = postResponseForm(apiUrl, param, header);
		try {
			JSONParser jsonParser = new JSONParser();
			return (JSONObject) jsonParser.parse(responseString);
		} catch (Exception e) {
			logger.error("Error parsing JSON response", e);
			throw new Exception("Failed to parse JSON response", e);
		}
	}
}
