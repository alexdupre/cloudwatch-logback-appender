package com.j256.cloudwatchlogbackappender;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.imds.Ec2MetadataResponse;

/**
 * Converter which knows about the task-id in ECS. Will return "not-in-ecs", "http-error", "interrupted", or "unknown"
 * if it wasn't able to use the container metadata URI. You can call {@link #setTaskId(String)} early in your
 * application if you want to set it yourself.
 * 
 * @author graywatson
 */
public class TaskIdConverter extends ClassicConverter {

	private static final String UNKNOWN_TASK_ID = "unknown";
	private static final String ECS_CONTAINER_METADATA_URI = "ECS_CONTAINER_METADATA_URI_V4";
	private static final String TASK_ARN_FIELD = "TaskARN";
	private static final Duration METADATA_TIMEOUT = Duration.ofSeconds(2);

	private static volatile String taskId;

	@Override
	public String convert(ILoggingEvent event) {
		String id = taskId;
		if (id == null) {
			id = lookupTaskId();
			taskId = id;
		}
		return id;
	}

	/**
	 * Set the task-id explicitly, which stops the lookup from the ECS container metadata. Setting it to null restores
	 * the lookup.
	 */
	public static void setTaskId(String taskId) {
		TaskIdConverter.taskId = taskId;
	}

	/**
	 * Stop the converter from reaching out to the ECS metadata service, unless the task-id was already set explicitly.
	 */
	static void disableLookup() {
		if (taskId == null) {
			setTaskId(UNKNOWN_TASK_ID);
		}
	}

	/**
	 * Pluck the task-id out of an ECS task metadata document. The task-id is the part after the last '/' of the
	 * TaskARN, for example: arn:aws:ecs:us-east-1:4########97:task/appname/8bbe67c9363a457894068e2259f5690c
	 */
	static String parseTaskId(String metadataJson) {
		Document document;
		try {
			document = Ec2MetadataResponse.create(metadataJson).asDocument();
		} catch (RuntimeException e) {
			// the metadata wasn't the json document that we expected
			return UNKNOWN_TASK_ID;
		}
		if (!document.isMap()) {
			return UNKNOWN_TASK_ID;
		}
		Document taskArn = document.asMap().get(TASK_ARN_FIELD);
		if (taskArn == null || !taskArn.isString()) {
			return UNKNOWN_TASK_ID;
		}
		String arn = taskArn.asString();
		int index = arn.lastIndexOf('/');
		if (index < 0 || index == arn.length() - 1) {
			return UNKNOWN_TASK_ID;
		}
		return arn.substring(index + 1);
	}

	/**
	 * Lookup our task-id from the container metadata.
	 */
	private static String lookupTaskId() {
		String metadataUri = System.getenv(ECS_CONTAINER_METADATA_URI);
		if (MiscUtils.isBlank(metadataUri)) {
			return "not-in-ecs";
		}

		HttpClient client = HttpClient.newBuilder().connectTimeout(METADATA_TIMEOUT).build();
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(metadataUri + "/task"))
				.timeout(METADATA_TIMEOUT)
				.GET()
				.build();
		HttpResponse<String> response;
		try {
			response = client.send(request, HttpResponse.BodyHandlers.ofString());
		} catch (IOException e) {
			return "http-error";
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return "interrupted";
		}
		return parseTaskId(response.body());
	}
}
