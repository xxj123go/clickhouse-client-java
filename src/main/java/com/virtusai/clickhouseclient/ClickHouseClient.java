package com.virtusai.clickhouseclient;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.DefaultAsyncHttpClient;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.virtusai.clickhouseclient.models.exceptions.HttpRequestException;
import com.virtusai.clickhouseclient.models.http.ClickHouseResponse;
import com.virtusai.clickhouseclient.utils.POJOMapper;

public class ClickHouseClient implements AutoCloseable {
	private static final Logger LOG = LoggerFactory.getLogger(ClickHouseClient.class);

	private static final String SELECT_FORMAT = "JSON";
	private static final String INSERT_FORMAT = "TabSeparated";

	private final String endpoint;
	private final AsyncHttpClient httpClient;
	

	public ClickHouseClient(String endpoint, String username, String password) {
		this.endpoint = endpoint;

		AsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
				.setRealm(new Realm.Builder(username, password)
						.setUsePreemptiveAuth(true)
						.setScheme(Realm.AuthScheme.BASIC)
						.build())
				.build();

		this.httpClient = new DefaultAsyncHttpClient(config);
	}

	public void close() {
		try {
			this.httpClient.close();
		} catch (Exception e) {
			LOG.error("Error closing http client", e);
		}
	}

	public <T> CompletableFuture<ClickHouseResponse<T>> get(String query, Class<T> clazz) {
		String queryWithFormat = query + " FORMAT " + SELECT_FORMAT;

		Request request = httpClient.prepareGet(endpoint)
				.addQueryParam("query", queryWithFormat)
				.build();

		return sendRequest(request).thenApply(POJOMapper.toPOJO(clazz));
	}

	public <T> CompletableFuture<ClickHouseResponse<T>> post(String query, Class<T> clazz) {
		String queryWithFormat = query + " FORMAT " + SELECT_FORMAT;

		Request request = httpClient.preparePost(endpoint)
				.setBody(queryWithFormat)
				.build();

		return sendRequest(request).thenApply(POJOMapper.toPOJO(clazz));
	}

	public CompletableFuture<Void> post(String query, List<Object[]> data) {
		String queryWithFormat = query + " FORMAT " + INSERT_FORMAT;

		Request request = httpClient.preparePost(endpoint)
				.addQueryParam("query", queryWithFormat)
				.setBody(tabSeparatedString(data))
				.build();

		return sendRequest(request).thenApply(rs -> null);
	}

	private CompletableFuture<String> sendRequest(Request request) {
		return httpClient.executeRequest(request).toCompletableFuture()
		.handle((response, t) -> {
			if (t != null) {
				LOG.error("Error sending request to endpoint=" + endpoint, t);
				throw new RuntimeException("Error sending request to endpoint=" + endpoint);
				
			} else {
				final int statusCode = response.getStatusCode();
				final String body = response.getResponseBody();

				if (statusCode != 200) {
					final String decodedUrl = decodedUrl(request);	
					
					HttpRequestException e = new HttpRequestException(statusCode, body, decodedUrl);
					LOG.error("[{}] {} : {}", statusCode, decodedUrl, body);

					throw e;
				}

				return body;
			}
		});
	}
	
	private static String tabSeparatedString(List<Object[]> data) {
		return data.stream().map(row -> Arrays.stream(row).map(col -> col.toString()).collect(Collectors.joining("\t"))).collect(Collectors.joining("\n"));
	}

	private static String decodedUrl(Request request) {
		final String url = request.getUrl();

		try {
			return URLDecoder.decode(url, StandardCharsets.UTF_8.name());
		} catch (UnsupportedEncodingException e) {
			return url;
		}
	}
}
