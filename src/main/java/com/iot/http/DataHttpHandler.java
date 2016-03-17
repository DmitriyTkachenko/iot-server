package com.iot.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iot.MessageQueueSender;
import com.iot.token.JwtTokenService;
import com.iot.token.TokenParseException;
import com.iot.token.TokenVerificationException;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.Methods;
import io.undertow.util.StatusCodes;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import static com.iot.DataSchema.DATA;
import static com.iot.DataSchema.DEVICE_ID;
import static com.iot.KafkaTopics.DATA_TOPIC;
import static com.iot.http.HttpUtils.*;
import static io.undertow.util.StatusCodes.UNAUTHORIZED;

public class DataHttpHandler implements HttpHandler {
	private final MessageQueueSender sender;
	private final JwtTokenService tokenService;
	private final ObjectMapper objectMapper;

	public DataHttpHandler(MessageQueueSender sender, JwtTokenService tokenService, ObjectMapper objectMapper) {
		this.sender = sender;
		this.tokenService = tokenService;
		this.objectMapper = objectMapper;
	}

	@Override
	public void handleRequest(HttpServerExchange exchange) throws Exception {
		if (!exchange.getRequestMethod().equals(Methods.POST)) {
			exchange.getResponseHeaders().put(Headers.ALLOW, "POST");
			exchange.setStatusCode(StatusCodes.METHOD_NOT_ALLOWED);
			exchange.setPersistent(false);
			exchange.getResponseSender().close();
			return;
		}

		System.out.println(exchange.getRequestHeaders().get(Headers.AUTHORIZATION).getFirst());
		exchange.getRequestReceiver().receiveFullBytes(this::processMessage,
				(exch, e) -> sendServerError(exchange));
	}

	private void processMessage(HttpServerExchange exchange, byte[] message) {
		exchange.dispatch(); // do not end exchange when this method returns, because saving to kafka is async

		String authorizationHeader = exchange.getRequestHeaders().get("Authorization").get(0);
		Map<String, Object> tokenPayload;
		try {
			tokenPayload = tokenService.verifyToken(authorizationHeader);
		} catch (TokenParseException e) {
			sendError(exchange, "Token could not be parsed", UNAUTHORIZED);
			return;
		} catch (TokenVerificationException e) {
			sendError(exchange, "Token could not be verified", UNAUTHORIZED);
			return;
		}

		JsonNode json;
		try {
			json = objectMapper.readTree(message);
		} catch (IOException e) {
			sendRequestError(exchange);
			return;
		}

		Optional<String> deviceId = getDeviceId(tokenPayload);
		if (!deviceId.isPresent()) {
			sendRequestError(exchange);
			return;
		}

		JsonNode data = json.get(DATA);
		sender.send(DATA_TOPIC, deviceId.get(), data.toString())
				.thenRun(() -> exchange.getResponseSender().close())
				.exceptionally((throwable -> sendServerError(exchange)));
	}

	private Optional<String> getDeviceId(Map<String, Object> tokenPayload) {
		if (tokenPayload == null)
			return Optional.empty();

		Object o = tokenPayload.get(DEVICE_ID);

		if (o instanceof String) {
			String deviceId = (String) o;
			return deviceId.isEmpty() ? Optional.empty() : Optional.of(deviceId);
		}

		return Optional.empty();
	}
}
